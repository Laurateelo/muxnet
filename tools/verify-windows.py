"""Run the supplied HTTP suite and a controlled @Valid experiment on Windows."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from urllib.error import URLError


project, evidence = map(Path, sys.argv[1:3])
sys.path.insert(0, str(project / 'tests'))
from verify_api import call, result


def query(schema, sql):
    completed = subprocess.run(
        [r'D:\mysql\bin\mysql.exe', '--default-character-set=utf8mb4',
         '--batch', '--skip-column-names', '--user=root', schema, '--execute=' + sql],
        capture_output=True, encoding='utf-8', check=True,
    )
    return completed.stdout.strip()


@contextmanager
def application(arguments, schema, port, log_name):
    url = f'http://localhost:{port}'
    environment = {
        **os.environ,
        'DB_URL': f'jdbc:mysql://127.0.0.1:3306/{schema}?createDatabaseIfNotExist=true&serverTimezone=Asia/Shanghai',
        'DB_USER': 'root',
        'DB_PASSWORD': '',
        'SERVER_PORT': str(port),
    }
    with (evidence / log_name).open('wb') as log:
        process = subprocess.Popen(
            ['java', '-Dfile.encoding=UTF-8', *arguments], cwd=project,
            env=environment, stdout=log, stderr=subprocess.STDOUT,
        )
        try:
            for _ in range(45):
                if process.poll() is not None:
                    raise RuntimeError(f'Java exited during startup. See {log_name}.')
                try:
                    status, _, _, body = call(url, 'GET', '/students')
                    if status == 200 and body == result(data=[]):
                        break
                except (URLError, TimeoutError, json.JSONDecodeError):
                    pass
                time.sleep(1)
            else:
                raise RuntimeError(f'Empty schema {schema} did not become ready. See {log_name}.')
            yield url
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)


payload = {'name': '', 'age': 20}
with application(['-jar', str(project / 'target/lab3-demo-1.0.jar')],
                 'lab3_verify', 8081, 'app-verified.log') as url:
    on_status, _, _, on_body = call(url, 'POST', '/students', payload)
    on_count = query('lab3_verify', 'SELECT COUNT(*) FROM student')
    if (on_status, on_body, on_count) != (200, result(400, '姓名不能为空'), '0'):
        raise RuntimeError('The validation-enabled control did not reject the empty name.')
    subprocess.run(
        [sys.executable, str(project / 'tests/verify_api.py'), '--base-url', url,
         '--output', str(evidence / 'http-results.json')], check=True,
    )
    rows = query('lab3_verify', 'SELECT id, name, age FROM student ORDER BY id')
    if rows != '1\t张三丰\t22\n3\t下界\t1\n4\t上界\t120':
        raise RuntimeError('Unexpected database state after the 29-case suite.')

variant = evidence.parent / 'without-valid'
variant.mkdir()
classes = variant / 'classes'
shutil.copytree(project / 'target/classes', classes)
source = project / 'src/main/java/com/example/demo/controller/StudentController.java'
text = source.read_text(encoding='utf-8')
if text.count('@Valid @RequestBody') != 2:
    raise RuntimeError('Unexpected StudentController validation annotations.')
patched = variant / source.name
patched.write_text(text.replace('@Valid @RequestBody', '@RequestBody'), encoding='utf-8')
classpath = os.pathsep.join([str(classes), str(project / 'target/dependency/*')])
subprocess.run(
    ['javac', '--release', '17', '-parameters', '-encoding', 'UTF-8', '-cp', classpath,
     '-d', str(classes), str(patched)], check=True,
)
with application(['-cp', classpath, 'com.example.demo.DemoApplication'],
                 'lab3_without_valid', 8082, 'app-no-valid-verified.log') as url:
    off_status, _, _, off_body = call(url, 'POST', '/students', payload)
    list_status, _, _, listing = call(url, 'GET', '/students')
    off_rows = query('lab3_without_valid', 'SELECT id, CHAR_LENGTH(name), age FROM student')
    saved = {'id': 1, **payload}
    if (off_status, off_body, list_status, listing, off_rows) != (
        200, result(data=saved), 200, result(data=[saved]), '1\t0\t20'
    ):
        raise RuntimeError('The validation-disabled variant did not persist the expected empty string.')

(evidence / 'database-check.txt').write_text(
    'lab3_verify: id\tname\tage\n' + rows + '\n\n'
    'lab3_without_valid: id\tCHAR_LENGTH(name)\tage\n' + off_rows + '\n', encoding='utf-8',
)
(evidence / 'no-valid-results.json').write_text(json.dumps({
    'request': payload,
    'with_valid': {'schema': 'lab3_verify', 'port': 8081, 'http_status': on_status,
                   'response': on_body, 'row_count_after_request': int(on_count)},
    'without_valid': {'schema': 'lab3_without_valid', 'port': 8082, 'http_status': off_status,
                      'response': off_body, 'follow_up': listing, 'mysql_row': [1, 0, 20]},
    'passed': True,
}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('29 HTTP cases, two isolated MySQL checks, and the @Valid comparison passed.')

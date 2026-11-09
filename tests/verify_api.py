#!/usr/bin/env python3
"""使用真实 HTTP 请求验证实验；仅在新建的空实验数据库上运行。标准库，无额外依赖。"""
import argparse
from datetime import datetime
import json
from pathlib import Path
import sys
import urllib.request
import urllib.error

def result(code=200, msg='成功', data=None):
    return {'code': code, 'msg': msg, 'data': data}

def student(sid, name, age):
    return {'id': sid, 'name': name, 'age': age}

def call(base, method, path, payload=None):
    raw = payload if isinstance(payload, str) else (
        json.dumps(payload, ensure_ascii=False) if payload is not None else None)
    req = urllib.request.Request(base + path,
        data=raw.encode('utf-8') if raw is not None else None,
        headers={'Content-Type': 'application/json; charset=UTF-8'}, method=method)
    # localhost 测试不经系统代理。
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        response = opener.open(req, timeout=15)
    except urllib.error.HTTPError as e:
        response = e
    with response:
        text = response.read().decode('utf-8')
        return response.status, dict(response.headers), text, json.loads(text)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base-url', default='http://localhost:8080')
    p.add_argument('--output', default='local-results/http-results.json')
    a = p.parse_args()
    base = a.base_url.rstrip('/')
    status, _, _, initial = call(base, 'GET', '/students')
    if status != 200 or initial != result(data=[]):
        sys.exit('请先切换到新建的空数据库，再运行测试。脚本不会清空已有数据。')
    age_msg = '年龄必须在 1 到 120 之间'
    missing99 = result(404, '没有编号为 99 的学生')
    s1, s2 = student(1, '张三', 20), student(2, '李四', 21)
    changed = student(1, '张三丰', 22)
    cases = [
        ('新增张三', 'POST', '/students', {'name':'张三','age':20}, result(data=s1)),
        ('新增李四', 'POST', '/students', {'name':'李四','age':21}, result(data=s2)),
        ('查询全部', 'GET', '/students', None, result(data=[s1,s2])),
        ('按编号查询', 'GET', '/students/1', None, result(data=s1)),
        ('修改学生', 'PUT', '/students/1', {'name':'张三丰','age':22}, result()),
        ('删除学生', 'DELETE', '/students/2', None, result()),
        ('查询不存在的学生', 'GET', '/students/99', None, missing99),
        ('删除不存在的学生', 'DELETE', '/students/99', None, missing99),
        ('姓名为空字符串', 'POST', '/students', {'name':'','age':20}, result(400,'姓名不能为空')),
        ('年龄超上限', 'POST', '/students', {'name':'王五','age':200}, result(400,age_msg)),
        ('错误 JSON', 'POST', '/students', '{"name": "王五", "age": }', result(400,'请求数据格式错误，请检查 JSON')),
        ('除零异常兜底', 'GET', '/test/error', None, result(500,'服务器内部错误，请联系管理员')),
        ('选做一 地址不存在', 'GET', '/student', None, result(404,'接口地址不存在')),
        ('选做二 新增同名学生', 'POST', '/students', {'name':'张三丰','age':23}, result(400,'姓名已存在')),
        ('修改不存在的学生', 'PUT', '/students/99', {'name':'不存在','age':20}, missing99),
        ('姓名仅包含空格', 'POST', '/students', {'name':'   ','age':20}, result(400,'姓名不能为空')),
        ('缺失姓名字段', 'POST', '/students', {'age':20}, result(400,'姓名不能为空')),
        ('年龄为 null', 'POST', '/students', {'name':'空年龄','age':None}, result(400,'年龄不能为空')),
        ('年龄为零', 'POST', '/students', {'name':'下界外','age':0}, result(400,age_msg)),
        ('年龄下界 1', 'POST', '/students', {'name':'下界','age':1}, result(data=student(3,'下界',1))),
        ('年龄上界 120', 'POST', '/students', {'name':'上界','age':120}, result(data=student(4,'上界',120))),
        ('更新采用路径编号', 'PUT', '/students/1', {'id':999,'name':'张三丰','age':22}, result()),
        ('验证修改目标', 'GET', '/students/1', None, result(data=changed)),
        ('更新也校验姓名', 'PUT', '/students/1', {'name':'','age':22}, result(400,'姓名不能为空')),
        ('重复提交相同更新', 'PUT', '/students/1', {'name':'张三丰','age':22}, result()),
        ('年龄紧邻上界 121', 'POST', '/students', {'name':'上界外','age':121}, result(400,age_msg)),
        ('查询已删除学生', 'GET', '/students/2', None, result(404,'没有编号为 2 的学生')),
        ('缺失年龄字段', 'POST', '/students', {'name':'缺年龄'}, result(400,'年龄不能为空')),
        ('最终数据完整性', 'GET', '/students', None,
         result(data=[changed,student(3,'下界',1),student(4,'上界',120)])),
    ]
    records=[]
    checkpoints=[]
    for i,(name,method,path,body,expected) in enumerate(cases, 1):
        started=datetime.now().astimezone().isoformat(timespec='seconds')
        status,headers,raw,actual=call(base,method,path,body)
        passed=status==200 and actual==expected and 'application/json' in headers.get('Content-Type','')
        rec={'number':i,'name':name,'time':started,'method':method,'url':base+path,
             'request_body':body,'http_status':status,'response_headers':headers,
             'response_raw':raw,'response':actual,'expected':expected,'passed':passed}
        records.append(rec)
        print(f'{i:02d} {"PASS" if passed else "FAIL"} {method:6s} {path} | {name}')
        if i==12:
            hs,_,rt,rs=call(base,'GET','/students')
            checkpoints.append({'name':'12 项必做测试后的数据库状态','http_status':hs,
                'response_raw':rt,'response':rs,'passed':hs==200 and rs==result(data=[changed])})
    report={'base_url':base,'run_at':datetime.now().astimezone().isoformat(timespec='seconds'),
        'total':len(records),'passed':sum(r['passed'] for r in records),
        'cases':records,'checkpoints':checkpoints}
    out=Path(a.output)
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(f"RESULT: {report['passed']}/{report['total']} passed; evidence: {out}")
    return 0 if all(r['passed'] for r in records+checkpoints) else 1

if __name__ == '__main__':
    sys.exit(main())

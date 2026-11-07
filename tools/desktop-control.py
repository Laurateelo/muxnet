"""Bounded native desktop automation for this disposable lab VM.

Commands are read only from this run's checked-out repository branch and must
name the exact run ID. It supports UI input, screenshots, and the lab IDE helper.
It does not evaluate supplied code or execute arbitrary shell commands.
"""
from pathlib import Path
import ctypes,datetime,json,os,subprocess,sys,time,traceback
from ctypes import wintypes
import requests,pyautogui
from PIL import ImageGrab

BASE=Path('D:/lab2-ci'); OUT=BASE/'evidence'; OUT.mkdir(exist_ok=True)
RUN=os.environ['GITHUB_RUN_ID']; REPO=os.environ['GITHUB_REPOSITORY']; REF=os.environ['GITHUB_REF_NAME']
URL=f'https://api.github.com/repos/{REPO}/contents/control/commands.json'
READ_HEADERS={'Accept':'application/vnd.github.raw+json','Authorization':'Bearer '+os.environ['LAB_REPOSITORY_READ_TOKEN']}
pyautogui.PAUSE=.25
pyautogui.FAILSAFE=True
user32=ctypes.windll.user32

def windows():
    result=[]
    callback_type=ctypes.WINFUNCTYPE(wintypes.BOOL,wintypes.HWND,wintypes.LPARAM)
    def collect(handle,_):
        if user32.IsWindowVisible(handle):
            title=ctypes.create_unicode_buffer(1024)
            user32.GetWindowTextW(handle,title,len(title))
            if title.value:
                rect=wintypes.RECT();pid=wintypes.DWORD()
                user32.GetWindowRect(handle,ctypes.byref(rect))
                user32.GetWindowThreadProcessId(handle,ctypes.byref(pid))
                result.append({'title':title.value,'handle':int(handle),'pid':pid.value,'rect':[rect.left,rect.top,rect.right,rect.bottom]})
        return True
    user32.EnumWindows(callback_type(collect),0)
    return result

def capture(identifier,error=None):
    folder=OUT/f'live-{identifier:03d}';folder.mkdir(exist_ok=True)
    # Pillow's Windows screen capture excludes the cursor. Move it off content as well.
    screen=pyautogui.size();pyautogui.moveTo(screen.width-2,screen.height-2)
    time.sleep(.5)
    ImageGrab.grab(all_screens=True).save(folder/'desktop.png')
    state={'run_id':RUN,'time':datetime.datetime.now(datetime.timezone.utc).isoformat(),'size':list(screen),'windows':windows(),'error':error}
    try:state['backend']=requests.get('http://localhost:8080/students',timeout=3).text
    except Exception as e:state['backend']=str(e)
    (folder/'state.json').write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')
    try:
        inspection=subprocess.run([sys.executable,str(Path(__file__).with_name('inspect-ui.py'))],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=15,env={**os.environ,'PYTHONIOENCODING':'utf-8'})
        ui=inspection.stdout+'\n'+inspection.stderr
    except subprocess.TimeoutExpired:
        ui='Accessibility inspection exceeded 15 seconds. Use the original screenshot and native window list.'
    (folder/'ui.txt').write_text(ui,encoding='utf-8')
    events=OUT/'ide-events.txt'
    if events.exists():(folder/'ide-events.txt').write_bytes(events.read_bytes())
    result=subprocess.run(['node',str(BASE/'upload-artifact.cjs'),f'lab2-live-{RUN}-{identifier:03d}',str(folder)],capture_output=True,text=True,timeout=120)
    print(result.stdout,flush=True)
    if result.returncode:print(result.stderr,flush=True)
    return folder

def perform(op):
    kind=op['kind']
    if kind=='click':pyautogui.click(int(op['x']),int(op['y']),clicks=int(op.get('count',1)),button=op.get('button','left'))
    elif kind=='move':pyautogui.moveTo(int(op['x']),int(op['y']))
    elif kind=='keys':pyautogui.hotkey(*op['keys'])
    elif kind=='press':pyautogui.press(op['key'],presses=int(op.get('count',1)),interval=.1)
    elif kind=='type':
        # Unicode SendInput; does not alter the clipboard or application internals.
        class KeyInput(ctypes.Structure):
            _fields_=[('wVk',ctypes.c_ushort),('wScan',ctypes.c_ushort),('dwFlags',ctypes.c_ulong),('time',ctypes.c_ulong),('dwExtraInfo',ctypes.c_void_p)]
        class MouseInput(ctypes.Structure):
            _fields_=[('dx',ctypes.c_long),('dy',ctypes.c_long),('mouseData',ctypes.c_ulong),('dwFlags',ctypes.c_ulong),('time',ctypes.c_ulong),('dwExtraInfo',ctypes.c_void_p)]
        class HardwareInput(ctypes.Structure):
            _fields_=[('uMsg',ctypes.c_ulong),('wParamL',ctypes.c_ushort),('wParamH',ctypes.c_ushort)]
        class U(ctypes.Union):_fields_=[('ki',KeyInput),('mi',MouseInput),('hi',HardwareInput)]
        class Input(ctypes.Structure):_fields_=[('type',ctypes.c_ulong),('u',U)]
        units=op['text'].encode('utf-16-le')
        for i in range(0,len(units),2):
            code=int.from_bytes(units[i:i+2],'little')
            for flag in [4,6]:
                event=Input(type=1,u=U(ki=KeyInput(0,code,flag,0,None)))
                if user32.SendInput(1,ctypes.byref(event),ctypes.sizeof(Input))!=1:raise RuntimeError('Unicode input failed')
    elif kind=='scroll':pyautogui.scroll(int(op['amount']))
    elif kind=='activate':
        handle=int(op['handle']);user32.ShowWindow(handle,9);user32.SetForegroundWindow(handle)
    elif kind=='maximize':
        handle=int(op['handle']);user32.ShowWindow(handle,3);user32.SetForegroundWindow(handle)
    elif kind=='wait':time.sleep(min(max(float(op.get('seconds',1)),0),20))
    elif kind=='idea':
        if op['action'] not in ['prepare','build-run','project','run-window']:raise ValueError('Unsupported IDE helper command')
        (BASE/'ide-command.txt').write_text(f'{time.time_ns()}:{op["action"]}',encoding='utf-8')
    elif kind=='save_figure':
        name=op['name']
        if not name.replace('_','').isalnum():raise ValueError('Invalid image name')
        folder=OUT/'screenshots';folder.mkdir(exist_ok=True)
        screen=pyautogui.size();pyautogui.moveTo(screen.width-2,screen.height-2);time.sleep(.8)
        ImageGrab.grab(all_screens=True).save(folder/(name+'.png'))
    elif kind=='capture':pass
    elif kind=='finish':return False
    else:raise ValueError('Unsupported operation '+kind)
    return True

capture(0)
seen=set();started=time.monotonic();done=False
while time.monotonic()-started<3600 and not done:
    try:
        r=requests.get(URL,params={'ref':REF},headers=READ_HEADERS,timeout=20);r.raise_for_status();data=r.json()
        if str(data.get('run_id'))!=RUN:time.sleep(4);continue
        for command in data.get('commands',[]):
            identifier=int(command['id'])
            if identifier in seen:continue
            seen.add(identifier)
            failure=None
            try:
                for op in command['ops']:
                    if not perform(op):done=True;break
            except Exception:failure=traceback.format_exc()
            time.sleep(float(command.get('settle',1)))
            capture(identifier,failure)
            if failure:print(failure,flush=True)
            if done:break
        time.sleep(3)
    except Exception as e:
        print(type(e).__name__+': '+str(e),flush=True);time.sleep(5)
(OUT/'desktop-session.json').write_text(json.dumps({'run_id':RUN,'finished_by_command':done,'commands':sorted(seen)},indent=2))
if not done:raise SystemExit('Desktop session reached its bounded time limit.')

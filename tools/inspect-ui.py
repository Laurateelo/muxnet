"""Optional accessibility observation, bounded by the parent capture process."""
from pywinauto import Desktop
for window in Desktop(backend='uia').windows():
    try:
        title=window.window_text()
        print(f'WINDOW {title} HWND={window.handle}',flush=True)
        if not any(k in title.lower() for k in ['apifox','idea','lab3','privacy','import','open','agreement','home','offline','request']):
            continue
        for control in window.descendants()[:450]:
            try:
                info=control.element_info
                print(f'{info.control_type} | {info.name} | id={info.automation_id} | rect={info.rectangle}',flush=True)
            except Exception:
                pass
    except Exception:
        pass

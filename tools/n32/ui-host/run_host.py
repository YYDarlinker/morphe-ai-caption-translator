"""Install/start/read a test fixture only on the explicitly verified local emulator."""
import argparse,hashlib,json,os,pathlib,re,subprocess,time,uuid

SDK=pathlib.Path(os.environ['LOCALAPPDATA'])/'Android/Sdk'
ADB=SDK/'platform-tools/adb.exe'
SERIAL='emulator-5580'
PACKAGE='app.morphe.android.youtube'

def adb(*args,check=True):
    return subprocess.run([str(ADB),'-s',SERIAL,*map(str,args)],capture_output=True,check=check,encoding='utf-8',errors='replace')

def main():
    p=argparse.ArgumentParser();p.add_argument('host_directory');p.add_argument('--mode',default='after');p.add_argument('--timeout',type=int,default=240)
    p.add_argument('--host-apk');p.add_argument('--width-dp',type=int);p.add_argument('--font-scale',type=float);p.add_argument('--dark',action='store_true');a=p.parse_args()
    out=pathlib.Path(a.host_directory).resolve();out.mkdir(parents=True,exist_ok=True)
    if (out/'ui-events.json').exists():p.error('Use a fresh evidence output directory; preserve every prior run')
    host=pathlib.Path(a.host_apk).resolve() if a.host_apk else out/'host-signed.apk'
    assert host.is_file(),host
    inputs=json.loads((host.parent/'host-inputs.json').read_text(encoding='utf-8'))
    assert hashlib.sha256(host.read_bytes()).hexdigest().upper()==inputs['signed_fixture_sha256'],'Fixture bytes do not match recorded inputs'
    if host.parent!=out:(out/'host-inputs.json').write_text(json.dumps(inputs,indent=2),encoding='utf-8')
    # Never auto-select a serial or send a write command to a phone.
    assert SERIAL.startswith('emulator-')
    model=adb('shell','getprop','ro.product.model').stdout.strip();sdk=adb('shell','getprop','ro.build.version.sdk').stdout.strip()
    assert 'sdk' in model.lower() and sdk=='35',(SERIAL,model,sdk)
    deadline=time.monotonic()+120
    while adb('shell','getprop','sys.boot_completed').stdout.strip()!='1':
        assert time.monotonic()<deadline,'Verified local emulator boot timed out'
        time.sleep(1)
    original_size=adb('shell','wm','size').stdout.strip();original_density=adb('shell','wm','density').stdout.strip()
    original_font=adb('shell','settings','get','system','font_scale').stdout.strip()
    (out/'local-display-before.json').write_text(json.dumps({'size':original_size,'density':original_density,'font_scale':original_font},indent=2),encoding='utf-8')
    changed_size=changed_font=False;events=None
    try:
        if a.width_dp:
            assert a.width_dp in (320,420),'Use only requested local WMS widths'
            density=int(re.findall(r'(?:Physical|Override) density:\s*(\d+)',original_density)[-1])
            height=int(re.findall(r'(?:Physical|Override) size:\s*(\d+)x(\d+)',original_size)[-1][1])
            adb('shell','wm','size',str(int(a.width_dp*density/160+.5))+'x'+str(height));changed_size=True
        if a.font_scale:
            assert a.font_scale in (1.0,1.3),'Use only requested local WMS font scales'
            adb('shell','settings','put','system','font_scale',a.font_scale);changed_font=True
        install=adb('install','-r','-t',host,check=False)
        (out/'install.log').write_text(install.stdout+install.stderr,encoding='utf-8');assert install.returncode==0
        adb('shell','am','force-stop',PACKAGE)
        run_id=str(uuid.uuid4())
        (out/'start.log').write_text(adb('shell','am','start','-W','-n',PACKAGE+'/app.yydarlinker.n32fixture.N32UiHost','--es','mode',a.mode,'--es','run-id',run_id,'--ez','dark',str(a.dark).lower()).stdout,encoding='utf-8')
        deadline=time.monotonic()+a.timeout
        while time.monotonic()<deadline:
            result=adb('shell','run-as',PACKAGE,'cat','files/n32-ui-events.json',check=False)
            try:
                observed=json.loads(result.stdout)
                if observed.get('mode')==a.mode and observed.get('run_id')==run_id:
                    events=observed
                    if any(e['event'] in ('PASS','FAIL') for e in events['events']):break
            except json.JSONDecodeError:pass
            if events is None and time.monotonic()>deadline-a.timeout+5 and not adb('shell','pidof',PACKAGE,check=False).stdout.strip():break
            time.sleep(.5)
        if events:(out/'ui-events.json').write_text(json.dumps(events,ensure_ascii=False,indent=2),encoding='utf-8')
        (out/'logcat.txt').write_text(adb('logcat','-d','-v','threadtime','-s','N32_WMS:I','AndroidRuntime:E','WindowManager:E','CaptionUiWindows:W').stdout,encoding='utf-8')
        (out/'windows.txt').write_text(adb('shell','dumpsys','window','windows').stdout,encoding='utf-8')
        (out/'activities.txt').write_text(adb('shell','dumpsys','activity','activities').stdout,encoding='utf-8')
        if events:
            snapshot_names={e.get('file','') for e in events['events'] if e['event']=='actual_view_snapshot'}
            for name in snapshot_names:
                assert re.fullmatch(r'n32-[A-Za-z0-9_-]+\.png',name),name
                snapshot=subprocess.run([str(ADB),'-s',SERIAL,'exec-out','run-as',PACKAGE,'cat','files/'+name],capture_output=True,check=True)
                (out/name).write_bytes(snapshot.stdout)
        (out/'local-device.json').write_text(json.dumps({'serial':SERIAL,'model':model,'sdk':sdk,'mode':a.mode,
            'width_dp':a.width_dp,'font_scale':a.font_scale,'dark':a.dark,'host_apk':str(host),'source_sha256':inputs['source_sha256'],
            'scope':'SDK35 emulator-only fixture. No commands sent to physical devices; composed source is never installed.',
            'snapshot_kind':'Actual attached Android View.draw() raster, not a mock widget.'},indent=2),encoding='utf-8')
        status=[e for e in events['events'] if e['event'] in ('PASS','FAIL')] if events else []
        assert status,'Local WMS fixture timed out; preserved event/logcat files explain available evidence'
        print(json.dumps(status[-1],ensure_ascii=False,indent=2))
        if a.mode=='before':
            assert status[-1]['event']=='FAIL' and 'BadTokenException' in status[-1].get('error',''),'Before must reproduce resourceContext BadToken'
            print('N32_LOCAL_BADTOKEN_BEFORE_REPRODUCED')
        else:
            assert status[-1]['event']=='PASS','After must display a real WMS window'
            print('N32_LOCAL_WMS_UI_PASS')
    finally:
        if changed_size:
            override=re.search(r'Override size:\s*(\d+x\d+)',original_size)
            adb('shell','wm','size',override.group(1) if override else 'reset')
        if changed_font:
            if original_font in ('null',''):adb('shell','settings','delete','system','font_scale')
            else:adb('shell','settings','put','system','font_scale',original_font)
        (out/'local-display-restored.json').write_text(json.dumps({'size':adb('shell','wm','size').stdout.strip(),
            'density':adb('shell','wm','density').stdout.strip(),'font_scale':adb('shell','settings','get','system','font_scale').stdout.strip()},indent=2),encoding='utf-8')

if __name__=='__main__':main()

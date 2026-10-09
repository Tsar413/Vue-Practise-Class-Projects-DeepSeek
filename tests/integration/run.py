"""Run both explicit modes, then stop only isolated services and the test stub.

Normal services are never started/stopped here. Use the README wrapper on this
small VM to pause and reliably restore normal services around this command.
"""
import argparse,json,subprocess,sys,time,unittest
from pathlib import Path
from support import ROOT,RUNTIME

def env(action,mode='protocol'):
    subprocess.run([sys.executable,str(Path(__file__).with_name('environment.py')),action,'--mode',mode],cwd=ROOT,check=True)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--mode',choices=['off','protocol','all'],default='all');args=parser.parse_args()
    summary=[];failed=False
    try:
        env('stub-start')
        for mode in (['off','protocol'] if args.mode=='all' else [args.mode]):
            env('start',mode)
            try:
                pattern='test_unconfigured.py' if mode=='off' else 'test_boundaries.py'
                suite=unittest.defaultTestLoader.discover(str(Path(__file__).parent),pattern=pattern)
                result=unittest.TextTestRunner(verbosity=2).run(suite)
                summary.append({'mode':mode,'cases':result.testsRun,'failures':[test.id() for test,_ in result.failures],'errors':[test.id() for test,_ in result.errors],'skipped':[test.id() for test,_ in result.skipped],'realModelCalls':0})
                failed|=not result.wasSuccessful()
            finally:env('stop',mode)
    finally:
        # Even if a guard or test fails, the project script verifies the recorded
        # test process identity before stopping. No broad pkill or DB cleanup.
        try:env('stop')
        finally:env('stub-stop')
        RUNTIME.mkdir(exist_ok=True)
        output=RUNTIME/('boundary-results-'+time.strftime('%Y%m%d-%H%M%S')+'.json')
        output.write_text(json.dumps(summary,ensure_ascii=False,indent=2));output.chmod(0o600)
        print('Private scenario report:',output)
    return 1 if failed else 0

if __name__=='__main__':raise SystemExit(main())

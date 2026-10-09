"""Build a committed revision outside the working tree using existing tools.

Usage: python3 scripts/deploy/package-release.py /absolute/new/output-directory [commit]
Requires Java 21, Maven and npm on PATH; reuses installed frontend dependencies
only when their package-lock matches the requested commit. Never reads .runtime.
"""
import hashlib,json,os,shutil,subprocess,sys,tarfile,zipfile
from pathlib import Path

root=Path(subprocess.check_output(['git','rev-parse','--show-toplevel'],text=True).strip())
remote=subprocess.check_output(['git','remote','get-url','origin'],cwd=root,text=True).strip().removesuffix('.git')
assert remote=='https://github.com/Tsar413/Vue-Practise-Class-Projects-DeepSeek','Wrong repository'
commit=subprocess.check_output(['git','rev-parse',sys.argv[2] if len(sys.argv)>2 else 'HEAD'],cwd=root,text=True).strip()
out=Path(sys.argv[1]).resolve();assert not out.exists() and not out.is_relative_to(root),'Use a new directory outside repository'
out.mkdir(mode=0o700,parents=True);source=out/'source';source.mkdir()
archive=out/'source.tar'
with archive.open('wb') as f:subprocess.run(['git','archive',commit],cwd=root,stdout=f,check=True)
with tarfile.open(archive) as t:t.extractall(source,filter='data')
assert (source/'frontend/package-lock.json').read_bytes()==(root/'frontend/package-lock.json').read_bytes(),'Install matching dependencies in exported source before building; do not alter normal project'
assert (root/'frontend/node_modules').is_dir(),'Existing frontend dependencies required'
(source/'frontend/node_modules').symlink_to(root/'frontend/node_modules',target_is_directory=True)
env=dict(os.environ,VITE_API_BASE_URL='http://106.14.114.66',MAVEN_OPTS='-Xmx384m')
subprocess.run(['npm','test'],cwd=source/'frontend',env=env,check=True)
subprocess.run(['npm','run','build'],cwd=source/'frontend',env=env,check=True)
subprocess.run(['mvn','-B','-DargLine=-Xmx256m','package'],cwd=source/'backend',env=env,check=True)
release=out/'release';release.mkdir();(release/'backend').mkdir()
jar=source/'backend/target/vue-practice-backend.jar'
with zipfile.ZipFile(jar) as z:assert not any('devtools' in name for name in z.namelist())
shutil.copy2(jar,release/'backend/app.jar');shutil.copytree(source/'frontend/dist',release/'frontend')
shutil.copytree(source/'db',release/'db');(release/'scripts').mkdir()
for name in ['init-db.sh','migrate.py']:shutil.copy2(source/'scripts'/name,release/'scripts'/name)
if (source/'scripts/deploy').is_dir():shutil.copytree(source/'scripts/deploy',release/'deploy')
manifest={'commit':commit,'publicOrigin':env['VITE_API_BASE_URL'],'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'files':{str(f.relative_to(release)):hashlib.sha256(f.read_bytes()).hexdigest() for f in release.rglob('*') if f.is_file()}}
(release/'release.json').write_text(json.dumps(manifest,indent=2)+'\n')
with tarfile.open(out/(commit+'.tar.gz'),'w:gz') as t:
    for p in release.iterdir():t.add(p,arcname=p.name)
print('Release ready:',out/(commit+'.tar.gz'))

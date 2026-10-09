"""Run as root on the inspected NEW deployment server only. No existing DB overwrite."""
import hashlib,json,os,secrets,subprocess
from pathlib import Path

BASE=Path('/opt/vue-practice')
CONFIG=Path('/etc/vue-practice')
DB='vue_practice_public'
USER='vue_public_app'
def sql(statement):
    p=subprocess.run(['mysql','--batch','--skip-column-names'],input=statement,text=True,capture_output=True)
    if p.returncode: raise RuntimeError('Database setup failed; inspect locally without printing credentials')
    return p.stdout.strip()
def private(path,text):
    fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    with os.fdopen(fd,'w') as f:f.write(text)

assert not CONFIG.exists(), 'Existing deployment configuration; refuse first-install overwrite'
assert sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"+DB+"'")=='0'
assert sql("SELECT COUNT(*) FROM mysql.user WHERE User='"+USER+"'")=='0'
CONFIG.mkdir(mode=0o700)
password=secrets.token_hex(24)
source=BASE/'current'
env=dict(os.environ,MYSQL_HOME='/usr',DB_SOCKET='/run/mysqld/mysqld.sock',DB_NAME=DB,DB_USERNAME=USER,DB_PASSWORD=password)
p=subprocess.run(['bash',str(source/'scripts/init-db.sh')],env=env,capture_output=True,text=True)
assert p.returncode==0,'First initialization failed (output withheld to protect credentials)'
p=subprocess.run(['python3',str(source/'scripts/migrate.py'),'--database',DB],env=env,capture_output=True,text=True)
assert p.returncode==0,'Migration failed (inspect schema before retry)'
print(p.stdout)
sql(f"REVOKE ALL PRIVILEGES, GRANT OPTION FROM '{USER}'@'127.0.0.1'; GRANT SELECT,INSERT,UPDATE,DELETE ON `{DB}`.* TO '{USER}'@'127.0.0.1';")
accounts=[]
for id in ['DEMO_TEACHER','DEMO2026001','DEMO2026002','DEMO2026003']:
    pw=secrets.token_urlsafe(15);salt=secrets.token_hex(32)
    hashed=hashlib.sha256((salt+':'+pw).encode()).hexdigest()
    sql(f"UPDATE `{DB}`.sys_user SET password_salt='{salt}',password_hash='{hashed}' WHERE id='{id}';")
    accounts.append(dict(id=id,password=pw,role='TEACHER' if id=='DEMO_TEACHER' else 'STUDENT',purpose='reset-acceptance-only' if id=='DEMO2026003' else 'fictional-demo'))
private(CONFIG/'demo-accounts.json',json.dumps(accounts,ensure_ascii=False,indent=2)+'\n')
values={
 'SERVER_ADDRESS':'127.0.0.1','SERVER_PORT':'8100',
 'DB_URL':f'jdbc:mysql://127.0.0.1:3306/{DB}?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8&serverTimezone=GMT%2B8',
 'DB_USERNAME':USER,'DB_PASSWORD':password,'SPRING_JPA_HIBERNATE_DDL_AUTO':'validate',
 'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE':'8','SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE':'2',
 'REPAIR_FILES_ROOT':str(BASE/'data/repair-files'),'TEACHING_FILES_ROOT':str(BASE/'data/teaching-files'),
 'APP_CORS_ALLOWED_ORIGINS':'http://106.14.114.66',
 'LOGGING_FILE_NAME':'/var/log/vue-practice/application.log',
 'LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE':'10MB','LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY':'14',
 'LOGGING_LOGBACK_ROLLINGPOLICY_TOTAL_SIZE_CAP':'150MB',
 'AI_TUTOR_TIMEOUT_SECONDS':'60','AI_TUTOR_CONNECT_TIMEOUT_SECONDS':'5',
 'AI_TUTOR_MAX_TOKENS':'600','AI_TUTOR_MAX_CONTEXT_CHARS':'12000',
 'AI_TUTOR_DAILY_LIMIT_PER_STUDENT':'10','AI_TUTOR_MIN_INTERVAL_SECONDS':'15',
 'AI_TUTOR_MAX_CONCURRENT_PER_STUDENT':'1','AI_TUTOR_MAX_CONCURRENT_GLOBAL':'2',
 'AI_TUTOR_THINKING_ENABLED':'false'
}
private(CONFIG/'application.env',''.join(k+'='+json.dumps(v)+'\n' for k,v in values.items()))
print('Fresh synthetic schema initialized; demo passwords randomized; runtime grants restricted to DML.')

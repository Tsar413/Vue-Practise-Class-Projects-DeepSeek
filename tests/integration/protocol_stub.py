"""LOCAL PROTOCOL TEST DOUBLE. Never a real model response. Loopback only."""
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json,threading,socket
from support import FIXTURE_KEY

class State:
    lock=threading.Condition()
    mode='success'; records=[]; release=threading.Event()

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def reply(self,status,data,raw=False):
        body=data if raw else json.dumps(data,ensure_ascii=False).encode()
        try:
            self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError):pass
    def do_GET(self):
        if self.path!='/control/state':return self.reply(404,{})
        with State.lock:self.reply(200,{'mode':State.mode,'records':State.records[:]})
    def do_POST(self):
        data=json.loads(self.rfile.read(min(int(self.headers.get('Content-Length','0')),500000)))
        if self.path=='/control/mode':
            with State.lock:
                State.mode=data['mode'];State.release=threading.Event()
            return self.reply(200,{'ok':True})
        if self.path=='/control/release':State.release.set();return self.reply(200,{'ok':True})
        if self.path=='/control/wait':
            with State.lock:
                ok=State.lock.wait_for(lambda:len(State.records)>=data['count'],timeout=4)
            return self.reply(200,{'arrived':ok})
        if self.path!='/chat/completions':return self.reply(404,{})
        if self.headers.get('Authorization')!='Bearer '+FIXTURE_KEY:return self.reply(400,{'error':'Only synthetic credentials accepted'})
        with State.lock:
            mode=State.mode;release=State.release;State.records.append({'mode':mode,'request':data});State.lock.notify_all()
        if mode in ['hold','timeout']:release.wait(12)
        if mode=='disconnect':
            self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
        if mode in ['auth','rate','server']:return self.reply({'auth':401,'rate':429,'server':500}[mode],{'error':'PRIVATE_FIXTURE_UPSTREAM_DETAIL'})
        if mode=='emptybody':return self.reply(200,b'',True)
        if mode=='nonjson':return self.reply(200,b'<html>PRIVATE_FIXTURE_UPSTREAM_DETAIL</html>',True)
        text='[本地协议测试桩] 请检查字段，并按接口文档验证；这不是真实模型回答。'
        if mode=='empty':text=''
        if mode=='oversize':text='X'*300000
        if mode=='html':text='[本地协议测试桩] <script>document.body.dataset.injected="yes"</script><img src=x onerror="document.body.dataset.injected=\'yes\'"><a href="javascript:alert(1)">恶意链接</a> **安全文本**'
        return self.reply(200,{'choices':[{'message':{'role':'assistant','content':text}}]})

if __name__=='__main__':ThreadingHTTPServer(('127.0.0.1',18091),Handler).serve_forever()

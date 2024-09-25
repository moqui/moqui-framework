## 设置 HTTP 和 HTTPS 代理
在 gradle.properties 文件中  
systemProp.http.proxyHost=127.0.0.1  
systemProp.http.proxyPort=7890  

systemProp.https.proxyHost=127.0.0.1  
systemProp.https.proxyPort=7890  

## 设置 SOCKS 代理（如果需要）
systemProp.socksProxyHost=127.0.0.1  
systemProp.socksProxyPort=7890  

## 忽略的主机
systemProp.http.nonProxyHosts=localhost|127.0.0.0/8|::1  

系统更新重启：  
查看当前系统中运行的进程  
lsof -i:8080 | grep java  
根据进程查看运行位置  
lsof -p 11550 | grep cwd  
或者 pwdx 11550  
生产环境中需要借助vpn更新依赖环境  
terminal控制台中运行xcjs  

## 后台运行系统
你可以使用 nohup 命令在后台运行 Java 程序，这样即使你退出终端，程序也会继续运行。要运行 moqui.war 文件，可以使用以下命令：  

bash  
复制代码  
nohup java -jar moqui.war > moqui.log 2>&1 &  
解释如下：

nohup：允许命令在退出终端后继续运行。  
java -jar moqui.war：运行 moqui.war 文件。> moqui.log 2>&1：将输出和错误日志重定向到 moqui.log 文件。&：在后台运行命令。  
执行这个命令后，你可以使用 tail -f moqui.log 来查看程序的输出日志。如果你想停止这个进程，可以使用 ps aux | grep java 来查找进程的 PID，然后使用 kill <PID> 来终止。


## 公众号AI集成

- [x] 从本地请求ollama测试  
  ```
  curl http://localhost:11434/api/generate -d '{
  "model": "llama3.1",
  "prompt":"Why is the sky blue?"
  }'
  ```
- [x] 从服务器请求ollama测试  
  ```
  ssh -R 11434:localhost:11434 root@192.168.0.141   
  curl http://localhost:11434/api/generate -d '{
  "model": "llama3.1",
  "prompt": "Why is the sky blue?"
  }' -H "Content-Type: application/json"
  ```
- [x] 从moqui-wechat请求ollama测试  
  运行测试脚本
  ```
  ./gradlew :runtime:component:moqui-wechat:test --info
  ```



# 设置 HTTP 和 HTTPS 代理
在 gradle.properties 文件中  
systemProp.http.proxyHost=127.0.0.1  
systemProp.http.proxyPort=7890  

systemProp.https.proxyHost=127.0.0.1  
systemProp.https.proxyPort=7890  

# 设置 SOCKS 代理（如果需要）
systemProp.socksProxyHost=127.0.0.1  
systemProp.socksProxyPort=7890  

# 忽略的主机
systemProp.http.nonProxyHosts=localhost|127.0.0.0/8|::1  

系统更新重启：  
查看当前系统中运行的进程  
lsof -i:8080 | grep java  
根据进程查看运行位置  
lsof -p 11550 | grep cwd  
或者 pwdx 11550  
生产环境中需要借助vpn更新依赖环境  
terminal控制台中运行xcjs  

# 后台运行系统
你可以使用 nohup 命令在后台运行 Java 程序，这样即使你退出终端，程序也会继续运行。要运行 moqui.war 文件，可以使用以下命令：  

bash  
复制代码  
nohup java -jar moqui.war > moqui.log 2>&1 &  
解释如下：

nohup：允许命令在退出终端后继续运行。  
java -jar moqui.war：运行 moqui.war 文件。> moqui.log 2>&1：将输出和错误日志重定向到 moqui.log 文件。&：在后台运行命令。  
执行这个命令后，你可以使用 tail -f moqui.log 来查看程序的输出日志。如果你想停止这个进程，可以使用 ps aux | grep java 来查找进程的 PID，然后使用 kill <PID> 来终止。


## Welcome to Moqui Framework

[![license](https://img.shields.io/badge/license-CC0%201.0%20Universal-blue.svg)](https://github.com/moqui/moqui-framework/blob/master/LICENSE.md)
[![build](https://travis-ci.org/moqui/moqui-framework.svg)](https://travis-ci.org/moqui/moqui-framework)
[![release](https://img.shields.io/github/release/moqui/moqui-framework.svg)](https://github.com/moqui/moqui-framework/releases)
[![commits since release](http://img.shields.io/github/commits-since/moqui/moqui-framework/v3.0.0.svg)](https://github.com/moqui/moqui-framework/commits/master)
[![downloads](https://img.shields.io/github/downloads/moqui/moqui-framework/total.svg)](https://github.com/moqui/moqui-framework/releases)
[![downloads](https://img.shields.io/github/downloads/moqui/moqui-framework/v3.0.0/total.svg)](https://github.com/moqui/moqui-framework/releases/tag/v3.0.0)

[![Discourse Forum](https://img.shields.io/badge/moqui%20forum-discourse-blue.svg)](https://forum.moqui.org)
[![Google Group](https://img.shields.io/badge/google%20group-moqui-blue.svg)](https://groups.google.com/d/forum/moqui)
[![LinkedIn Group](https://img.shields.io/badge/linked%20in%20group-moqui-blue.svg)](https://www.linkedin.com/groups/4640689)
[![Gitter Chat at https://gitter.im/moqui/moqui-framework](https://badges.gitter.im/moqui/moqui-framework.svg)](https://gitter.im/moqui/moqui-framework?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Stack Overflow](https://img.shields.io/badge/stack%20overflow-moqui-blue.svg)](http://stackoverflow.com/questions/tagged/moqui)


For information about community infrastructure for code, discussions, support, etc see the Community Guide:

<https://www.moqui.org/docs/moqui/Community+Guide>

For details about running and deploying Moqui see:

<https://www.moqui.org/docs/framework/Run+and+Deploy>

Note that a runtime directory is required for Moqui Framework to run, but is not included in the source repository. The
Gradle get component, load, and run tasks will automatically add the default runtime (from the moqui-runtime repository).

For information about the current and near future status of Moqui Framework
see the [ReleaseNotes.md](https://github.com/moqui/moqui-framework/blob/master/ReleaseNotes.md) file.

For an overview of features see:

<https://www.moqui.org/docs/framework/Framework+Features>

Get started with Moqui development quickly using the Tutorial at:

<https://www.moqui.org/docs/framework/Quick+Tutorial>

For comprehensive documentation of Moqui Framework see the wiki based documentation on moqui.org (*running on Moqui HiveMind*):
 
<https://www.moqui.org/m/docs/framework>

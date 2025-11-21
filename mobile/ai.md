server这边Listen之后，监听一个端口，然后那个客户端go run main.go之后，会链接到这个server上来，建立一个长链接，然后server这边的其他端口比如80，443的流量进来之后，可以通过server.GetHTTPTransport获取到一个长链接为底层传输的tranport,当替换成这个transport之后，就能通过client这边的网络环境把请求发出去了。这是一个类似于ngrok,NATAPP的小玩意，目前已经能正常工作了，但是传输效率有点低，速度慢，请问哪里可以优化下啊


想让你实现一个手机android app,实现内网穿透，服务器端我已经实现了，客户端需要实现以下功能：
1. app启动后，自动连接到服务器端，保持心跳连接
2. app有一个启停按钮，可以打印日志

之前客户端一直没有app,但是有命令行启动，我把命令发给你：
#!/usr/bin/env bash
#
# Description: NAT Shell Script v2 by sean bugfan
# Date: 2022.8.9
# Copyright (C) 2022  bugfan <bugfan53@gmail.com>
# URL: https://www.i996.me
# Github: https://github.com/bugfan
# 
#!/usr/bin/env bash

token=$1

sys_protocol="https://"
sys_host="api.i996.me"
message_update_private="ClothoUpdatePrivate"
message_broadcast="ClothoBroadcast"
message_bind_port="ClothoAllocatedPort"
message_sync_public_host="syncpublichost"
message_sync_private_host="syncprivatehost"
server_addr="v2.i996.me"
server_port="8222"
public_host="xxxx.i996.me"
private_host="127.0.0.1:8080"
private_addr="127.0.0.1"
private_port="8080"
internal_fifo="./fifo.v2"
if [ ! -p ${internal_fifo} ]; then
mkfifo ${internal_fifo}
fi

log(){
t=$(date "+%Y-%m-%d.%H:%M:%S")
echo '['${t}']-'
}
echon(){
printf "\r%s" $1
}
include(){
tmp=$(echo $1 | grep "${2}")
if [[ "$tmp" != "" ]]
then
return 1
else
return 0
fi
}

output(){
_public_host=${public_host}
_private_host=${private_host}
while IFS= read -r line ; do
include "$line" $message_update_private
up=$?
if [ $up == 1 ]; then
ps -ef | grep 'ssh -o StrictHostKeyChecking=no -R' | awk '{print $2}' | xargs kill -9
continue
fi
include "$line" $message_sync_public_host
syn=$?
if [ $syn == 1 ]; then
_public_host=${line#*$message_sync_public_host}
continue
fi
include "$line" $message_sync_private_host
syn=$?
if [ $syn == 1 ]; then
_private_host=${line#*$message_sync_private_host}
continue
fi
include "$line" $message_bind_port
stat=$?
if [ ${#line} == '0' ]; then
continue
fi
if [ $stat == 0 ]; then
continue
fi
port=${line#*${message_bind_port}}
echo $(log)"i996内网穿透启动成功！！！"
echo $(log)"公网地址  =======> https://"$_public_host
echo $(log)"..                 http://"$_public_host
echo $(log)"..                 tcp://${_public_host}:${port}"
echo $(log)"内网地址  =======> "$_private_host
echo
echo $(log)'【温馨提示】您正在使用i996新版本！新版在上一版的基础上增加了tcp,ssh,ftp,smtp和websocket!'
echo $(log)'【温馨提示】新版暂不支持日志打印功能!详细情况请看https://www.i996.me或加QQ群883486121交流!'
done < "${internal_fifo}"
echo $(log)"正在尝试重连,请稍等～【可能您更新了配置,也可能是i996服务器管理员正在更新升级新功能】"
}
check_token(){
echo $(log)"验证Token中..."
if [ -z "${1}" ]; then
return 1
fi
msg=$(curl -s -X POST ''$sys_protocol$sys_host'/sys-auth' -H 'ClothoVersion: v2' -H 'Authorization: '${1}'')
if [ $? != 0 ]; then
return 2
fi

    include $msg $message_broadcast
    stat=$?
    if [ ${#msg} == '0' ]; then
        return 1
    fi
    if [ $stat == 0 ]; then
        return 1
    fi
    info=${msg#*$message_broadcast}
    public_host=${info%%|*}
    private_host=${info#*|}
    private_addr=${private_host%%:*}
    private_port=${info#*:}
    return 0
}
work(){
if [ ${#token} == '0' ];then
echo $(log)'请指定Token参数!(curl https://v2.i996.me | bash -s Token)'
exit 0
fi
check_token $token
state=$?
if [ $state == 0 ]; then
echo $(log)'Token验证通过!'
fi
if [ $state == 2 ]; then
echo $(log)'抱歉,认证失败,可能是您客户端到i996服务器网络请求被阻断了!'
# exit 0
return
fi
if [ $state == 1 ]; then
echo $(log)'Token验证失败!请关注"敲代码斯基"公众号获取Token!(免费)'
exit 0
fi
echo "${message_sync_public_host}${public_host}" > ${internal_fifo}
echo "${message_sync_private_host}${private_host}" > ${internal_fifo}
ssh -o StrictHostKeyChecking=no -R 0:${private_addr}:${private_port} ${token}@${server_addr} -p ${server_port} > ${internal_fifo} 2>&1
echo $(log)"网络断开了😭～"
}

retry_num=0
# kill output
reset(){
kill ${output_pid} 2>/dev/null
echo $(log)"正在重试..."
if [ $retry_num == 15 ]; then
echo $(log)"尝试多次都失败了，放弃了...."
exit 0
fi
retry_num=$[$retry_num+1]  
}

# finish func
finish(){
kill ${output_pid} 2>/dev/null
echo $(log)'撤退了～'
rm ${internal_fifo}
exit 0
}

# catch "Ctrl + c" "Exit"
trap finish EXIT SIGTERM SIGINT SIGQUIT

run(){

    while :
    do
        output & 2>/dev/null
        output_pid=$!
        # printf "worker pid:%s\n" ${output_pid}
        work
        reset
        sleep 2
    done
}
run


然后启动的话，就是执行cat cli.sh | bash -s xxxxx 就可以启动了，启动日志一般如下：
[2025-11-21.17:38:04]-验证Token中...

[2025-11-21.17:38:05]-Token验证通过!
[2025-11-21.17:38:06]-i996内网穿透启动成功！！！
[2025-11-21.17:38:06]-公网地址  =======> https://fuck.i996.me
[2025-11-21.17:38:06]-..                 http://fuck.i996.me
[2025-11-21.17:38:06]-..                 tcp://fuck.i996.me:35802
[2025-11-21.17:38:06]-内网地址  =======> 192.168.1.128:2080

[2025-11-21.17:38:06]-【温馨提示】您正在使用i996新版本！新版在上一版的基础上增加了tcp,ssh,ftp,smtp和websocket!
[2025-11-21.17:38:06]-【温馨提示】新版暂不支持日志打印功能!详细情况请看https://www.i996.me或加QQ群883486121交流!

所以，现在需要你用android kotlin或java写一个app，实现我cli.sh这个命令行客户端的功能


# golang tls反射隧道


### 使用方法
服务器端：
go run server.go

客户端：
go run client.go

这是server.go,这是conn.go,一共三个文件，我先告诉你这是个啥东西，这是一个golang 反射式tls隧道程序，类似于ngrok，frp这种玩意，能用作内网穿透，内外网隔离，穿防火墙。go run server.go运行在公网端，然后go run client.go运行在内网或者防火墙内，client会主动跟server建立长链接，建立成功后，server端另一个端口（可以是http/https/tcp等流量），可以借助这个底层的链接从client所在的网络发出去。现在这份代码是正常运行的。我现在想实现这么个东西，就是想实现一个android版本的client部分，理论上肯定是可行的，但是我不知道怎么写，请用kotlin帮我实现一个，包名就叫com.sean.i996，把安卓依赖的东西都写出来，我粘贴进去运行试试。谢谢


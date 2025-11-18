# golang tls反射隧道


### 使用方法
服务器端：
go run server.go

客户端：
go run client.go

client.go,server.go,conn.go,这三个文件我是我写的一个内网穿透程序，是一个golang 反射式tls隧道程序，类似于ngrok，frp这种玩意，能用作内网穿透，内外网隔离，穿防火墙。go run server.go运行在公网端监听tcp 3333，然后go run client.go运行在内网或者防火墙内，client会主动跟server 3333建立长链接，建立成功后,它在这链接内部实现了代理协议，server端监听另一个端口4444目前是一个http server（但也可以加一些其他协议的逻辑，比如tcp 5555），然后在server端执行 curl t.local.i443.cn:4444 就可以借助这个底层的链接从client所在的网络发出去，把server端的请求通过client所在的网络发到www.json.cn。现在这份代码是正常运行的。我现在想实现这么个东西，就是想实现一个android版本的client部分，理论上肯定是可行的，但是我不知道怎么写，请用kotlin帮我实现一个，包名就叫com.sean.i996，代码逻辑都写在MainActivity.kt就好，还有一些安卓依赖的东西也都写出来，我粘贴到我的项目里，运行试试



client.go,server.go,conn.go,这三个文件我是我写的一个内网穿透程序，是一个golang 反射式tls隧道程序，类似于ngrok，frp这种玩意，能用作内网穿透，内外网隔离，穿防火墙。go run server.go运行在公网端监听tcp 3333，然后go run client.go运行在内网或者防火墙内，client会主动跟server 3333建立长链接，建立成功后,它在这链接内部实现了代理协议，server端监听另一个端口4444目前是一个http server（但也可以加一些其他协议的逻辑，比如tcp 5555），然后在server端执行 curl t.local.i443.cn:4444 就可以借助这个底层的链接从client所在的网络发出去，把server端的请求通过client所在的网络发到www.json.cn。现在这份代码是正常运行的。我现在想实现这么个东西，就是想实现一个android版本的client部分，理论上肯定是可行的，但是我不知道怎么写，请用kotlin帮我实现一个，包名就叫com.sean.i996，代码逻辑都写在MainActivity.kt就好,其实最关键的就是client.go调用conn包时候里面的逻辑，它会在链接好的tls链接上，互传数据包，并在上面实现代理，当一个完整的http报文从server 4444进来时候，它会通过这个链接代理发到client端，且client还能解包，把目的地和端口解析出来，net.Dial做链接，然后把包内数据写出去。反过来从client到server也是这么个逻辑，你一定要严格参考我给你的文件来实现，另外可能还有一些安卓依赖的东西也都写出来，我粘贴到我的项目里，我运行试试


client.go,server.go,conn.go,这三个文件我是我写的一个内网穿透程序，是一个golang 反射式tls隧道程序，类似于ngrok，frp这种玩意，能用作内网穿透，内外网隔离，穿防火墙。go run server.go运行在公网端监听tcp 3333，然后go run client.go运行在内网或者防火墙内，client会主动跟server 3333建立长链接，建立成功后,它在这链接内部实现了代理协议，server端监听另一个端口4444目前是一个http server（但也可以加一些其他协议的逻辑，比如tcp 5555），然后在server端执行 curl t.local.i443.cn:4444 就可以借助这个底层的链接从client所在的网络发出去，把server端的请求通过client所在的网络发到www.json.cn。现在这份代码是正常运行的。我现在想实现这么个东西，就是想实现一个android版本的client部分，理论上肯定是可行的，但是我写了很多次，让ai也帮我写了很多次，都没实现有问题，不知道怎么写，我想在想的办法是，把原本go run client.go这块编译成so文件，让android直接调用不知道是否可行？这样的话android就不用实现协议部分了😭，还有大小端的问题。如果可以，你帮我实现so,并写一个MainActivity我运行试试

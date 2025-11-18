# golang tls反射隧道


### 使用方法
服务器端：
go run server.go

客户端：
go run client.go

跟md有啥关系，你看我tunnel里面的代码 ，一个server，一个client,s启动后监听3333，然后c启动会去链接这个3333，建立一个tls长链接，然后那个s端还有一个4444监听，可以接受流量，并借助这个tls链接，通过client所在的设备网络把请求发出去。你先读一下我的go代码。然后这个android项目， 是想实现tunnel里client一样的东西，kotlin编写，app启动后也能连接到网络里某个ip:3333上去，然后ip:4444能借助这个android app所在的网络发出去请求。
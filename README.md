Restitch
========

A generic microprocess that orchestrates calls to a configurable set of backing REST services

# Examples

Client issues a process invocation:
```
➜ curl -v -H "Content-Type:application/json" -d '{"pizzaId":101,"ingredients":["flour","eggs","milk","salt","kittens"],"pizzaName":"Chichen (P)itza"}' http://localhost:8080/invoke/775113c6-8f7a-4f0d-b5fd-9139727ef224
```

which is greeted with the following result
```
*   Trying ::1...
* TCP_NODELAY set
* Connected to localhost (::1) port 8080 (#0)
> POST /invoke/775113c6-8f7a-4f0d-b5fd-9139727ef224 HTTP/1.1
> Host: localhost:8080
> User-Agent: curl/7.58.0
> Accept: */*
> Content-Type:application/json
> Content-Length: 100
> 
* upload completely sent off: 100 out of 100 bytes
< HTTP/1.1 200 OK
< Host: localhost:8080
< User-Agent: curl/7.58.0
< Accept: */*
< Connection: keep-alive
< Content-Length: 10
< Content-Type: application/json
< 
* Connection #0 to host localhost left intact
["Yummy!"]%
```

Client can later (until process is dropped), pull the process result:
```
➜ curl -v http://localhost:8080/invoke/775113c6-8f7a-4f0d-b5fd-9139727ef224
```

which is greeted with the following result
```
*   Trying ::1...
* TCP_NODELAY set
* Connected to localhost (::1) port 8080 (#0)
> GET /invoke/775113c6-8f7a-4f0d-b5fd-9139727ef224 HTTP/1.1
> Host: localhost:8080
> User-Agent: curl/7.58.0
> Accept: */*
> 
< HTTP/1.1 200 OK
< Host: localhost:8080
< User-Agent: curl/7.58.0
< Accept: */*
< Connection: keep-alive
< Content-Length: 10
< Content-Type: application/json
< 
* Connection #0 to host localhost left intact
["Yummy!"]%
```

After the process was dropped:
```
➜ curl -v http://localhost:8080/invoke/775113c6-8f7a-4f0d-b5fd-9139727ef224

*   Trying ::1...
* TCP_NODELAY set
* Connected to localhost (::1) port 8080 (#0)
> GET /invoke/775113c6-8f7a-4f0d-b5fd-9139727ef224 HTTP/1.1
> Host: localhost:8080
> User-Agent: curl/7.58.0
> Accept: */*
> 
< HTTP/1.1 200 OK
< Host: localhost:8080
< User-Agent: curl/7.58.0
< Accept: */*
< Connection: keep-alive
< Content-Length: 0
< 
* Connection #0 to host localhost left intact
```

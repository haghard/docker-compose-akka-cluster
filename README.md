

### The project shows you how to scale the number of workers on one machine using `docker-compose`. ###

#### A sequence of docker commands ####

  * To build and publish the image `sbt docker:publishLocal`

  * To start one seed node and one worker node `docker-compose up -d`
     
  * To scale up the number of workers `docker-compose scale node=3`
   
  * To scale down the number of workers `docker-compose scale node=2`
  
  * To stop all processes `docker-compose stop`

  * To clean images `docker rm $(docker ps -a -q)`
  
  * Now you can build image again       

#### Docker utils ####
  
  For docker to show all ips `docker inspect -f '{{.Name}} - {{.NetworkSettings.IPAddress }}' $(docker ps -aq)`
  
  For docker-compose to show all ips `docker inspect -f '{{.Name}} - {{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $(docker ps -aq)`

#### Available urls ####

  Req/Resp `http GET 192.168.0.146:9000/members`

  Chunked resp `curl --no-buffer 192.168.0.146:9000/metrics`
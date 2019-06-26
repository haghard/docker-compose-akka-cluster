
### The project shows you how to scale the number of workers on one machine using `docker-compose`. ###

#### A sequence of docker commands ####
  
  * To set env vars we need
    
    `export MASTER_NODE=master`

    `export HTTP_PORT=9000`
    
    `export AKKA_PORT=2551`
    
    `export SEED_JMX_PORT=1089`
    
    `export HOST=192.168.77.10`
    
    `export TZ=UTC`

export MASTER_NODE=master
export HTTP_PORT=9000 
export AKKA_PORT=2551    
export SEED_JMX_PORT=1089    
export HOST=192.168.77.10
export TZ=UTC
    
  * Build and publish the image `sbt docker`
  
  * Start one seed node and one worker node `docker-compose -f docker-compose2.yml up -d`
     
  * Scale up the number of workers `docker-compose -f docker-compose2.yml scale worker=3`
   
  * Scale down the number of workers `docker-compose -f docker-compose2.yml scale worker=2`
  
  * Stop all processes `docker-compose -f docker-compose2.yml stop`

  * Delete images
      docker-compose -f docker-compose2.yml rm seed & docker-compose -f docker-compose2.yml rm worker
    
  * Now you can build image again

         
         
         
         
#### A sequence of docker commands to run on static network ####
  
  * To set env vars we need
    
    `export SEED_NODE=172.16.2.2`

    `export HTTP_PORT=9000`
    
    `export AKKA_PORT=2551`
    
    `export SEED_JMX_PORT=1089`
    
    `export HOST=192.168.77.10`
  
  * To build and publish the image `sbt docker`

  * To start one seed node and one worker node `docker-compose -f docker-compose.yml up -d`
     
  * To scale up the number of workers `docker-compose scale worker=3`
   
  * To scale down the number of workers `docker-compose scale worker=2`
  
  * To stop all processes `docker-compose stop`

  * To clean images `docker rm $(docker ps -a -q)`
  
  * Now you can build image again

#### Docker commands, utils ####
  
  For docker to show all ips `docker inspect -f '{{.Name}} - {{.NetworkSettings.IPAddress }}' $(docker ps -aq)`
  
  For docker-compose to show all ips `docker inspect -f '{{.Name}} - {{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $(docker ps -aq)`


  https://docs.docker.com/compose/compose-file/#ipv4-address-ipv6-address
  https://www.digitalocean.com/community/tutorials/how-to-provision-and-manage-remote-docker-hosts-with-docker-machine-on-ubuntu-16-04

  docker-compose rm seed 
  docker-compose rm worker
    
  docker network ls
  docker network rm bfb14b518775 a671ca262355    

  docker pause asdgfasd 

#### Available urls ####

  Req/Resp `http GET 192.168.77.10:9000/members`

  Chunked resp `curl --no-buffer 192.168.77.10:9000/metrics`
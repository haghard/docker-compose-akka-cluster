### The project shows how to run akka cluster using `docker-compose`.

##  Ideas

https://groups.google.com/forum/#!topic/akka-user/MO-4XhwhAN0


Sharding + Replication

RingMaster is managed by cluster singleton. It holds hash ring and routes all incoming requests to a particular shard region.          

Each cluster node starts knowing its shard name. For example: alpha, betta, gamma.
Each shard writes to/reads from its own shard region. Therefore, if we have the following set of shards: 
alpha, betta, gamma then we also have 3 independently running shard regions, each of which knows nothing about others. 
Each shard region is being used only inside a particular shard. Each shard becomes its own distributed system since it gets replications.

On each node that belongs to alpha we allocate only one sharded entity. Combination of host ip and port is used as shard and entity names, therefore 
 it guarantees that each node runs only one instance of sharded entity. Each sharded entity has the replicator actor inside.  


Example paths:

akka://dc@127.0.0.1:2551/system/sharding/devices/127.0.0.1-2551/127.0.0.1-2551] demo.DeviceShadow - * * *  Wake up device: alpha  * * *
akka://dc@127.0.0.2:2551/system/sharding/devices/127.0.0.2-2551/127.0.0.2-2551] demo.DeviceShadow - * * *  Wake up device: alpha  * * *
akka://dc@127.0.0.3:2551/system/sharding/devices/127.0.0.3-2551/127.0.0.3-2551] demo.DeviceShadow - * * *  Wake up device: alpha  * * *


Next question to address:
 When we add a new shard, say betta, in an operational cluster of 2 alpha nodes ([alpha -> 127.0.0.1-2551,127.0.0.2-2551]), 
 we need to transfer data, that from now on is associated with betta [alpha -> 127.0.0.1-2551,127.0.0.2-2551, betta -> 127.0.0.10-2551]
 ???
 

## Docker compose setup  

The docker-compose2.yml by default runs: 
 * 2 replicas for alpha
 * 1 replica for betta
 * 1 replica for gamma

Later you can scale up and down the number of replicas for these shards.
All replicas that have the same shard name should be responsible to the same range of keys and should have independent replicators on it.     

#### A sequence of docker commands ####
  
  * First of all we need to export these env vars
    
    `export MASTER_DNS=master`

    `export HTTP_PORT=9000`
    
    `export AKKA_PORT=2551`
    
    `export HOST=192.168.77.10`
    
    `export TZ=UTC`
    
  * Build and publish the image `sbt -DSHARD=docker docker`
  
  * Start one seed node and one worker node `docker-compose -f docker-compose2.yml up -d`
     
  * Scale up the number of workers `docker-compose -f docker-compose2.yml scale workerA=3`
   
  * Scale down the number of workers `docker-compose -f docker-compose2.yml scale workerA=2`
  
  * Stop all processes `docker-compose -f docker-compose2.yml stop`
  
  * Delete all processes `docker-compose -f docker-compose2.yml rm`
    
  * Now you can build the image again

## Run with sbt
 
```
sbt -DSHARD=a runA0
sbt -DSHARD=a runA1
sbt -DSHARD=a runA2

sbt -DSHARD=b runB0
sbt -DSHARD=b runB1
sbt -DSHARD=b runB2

sbt -DSHARD=g runG0
sbt -DSHARD=g runG1
sbt -DSHARD=g runG2
```


### Crop circle

  `http GET 192.168.77.10:9000/view`

  `http GET 192.168.77.10:9000/view1`

  `http GET 192.168.77.10:9000/members`

   `http://192.168.77.10:9000/rng`

### Cluster members

   `http GET 192.168.77.10:9000/cluster/members`
    

### Jvm metrics 
  `curl --no-buffer 192.168.77.10:9000/metrics`

### Ping device
  
  `http GET 192.168.77.10:9000/device/1`  


#### Docker commands ####
  
  For docker to show all ips `docker inspect -f '{{.Name}} - {{.NetworkSettings.IPAddress }}' $(docker ps -aq)`
  
  For docker-compose to show all ips `docker inspect -f '{{.Name}} - {{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $(docker ps -aq)`


  https://docs.docker.com/compose/compose-file/#ipv4-address-ipv6-address
  https://www.digitalocean.com/community/tutorials/how-to-provision-and-manage-remote-docker-hosts-with-docker-machine-on-ubuntu-16-04

  docker-compose rm seed 
  docker-compose rm worker
    
  docker network ls
  docker network rm bfb14b518775 a671ca262355    

  docker pause asdgfasd 

##  Links

https://speedcom.github.io/dsp2017/2017/04/13/justindb-replication-and-partitioning.html
 
### Akka

https://doc.akka.io/docs/akka/2.5.23/typed/cluster-sharding.html

https://doc.akka.io/docs/akka/current/distributed-data.html

https://groups.google.com/forum/#!topic/akka-user/MO-4XhwhAN0

https://www.digitalocean.com/community/tutorials/how-to-provision-and-manage-remote-docker-hosts-with-docker-machine-on-ubuntu-16-04

https://github.com/mckeeh3/akka-java-cluster-kubernetes/tree/d714ad5651ee4dc84464d1995be3c2d3ae9ca684

https://doc.akka.io/docs/akka-management/current/cluster-http-management.html


### Docker  

https://docs.docker.com/compose/compose-file/#resources

https://github.com/chbatey/docker-jvm-akka/blob/master/docker-compose.yml

https://dzone.com/articles/docker-container-resource-management-cpu-ram-and-i

http://www.batey.info/docker-jvm-k8s.html



Ring [alpha -> 127.0.0.1-2551,127.0.0.2-2551,127.0.0.3-2551] 

akka://dc@127.0.0.1:2551/system/sharding/devices/127.0.0.1-2551/127.0.0.1-2551] demo.DeviceShadow - * * *  Wake up device: alpha  * * *
akka://dc@127.0.0.2:2551/system/sharding/devices/127.0.0.2-2551/127.0.0.2-2551] demo.DeviceShadow - * * *  Wake up device: alpha  * * *
akka://dc@127.0.0.3:2551/system/sharding/devices/127.0.0.3-2551/127.0.0.3-2551] demo.DeviceShadow - * * *  Wake up device: alpha  * * *
 
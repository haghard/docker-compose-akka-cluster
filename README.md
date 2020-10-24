### The project shows how to run akka cluster using `docker-compose`.

##  Ideas

https://groups.google.com/forum/#!topic/akka-user/MO-4XhwhAN0


Cluster-sharding keywords: scale, consistency and failover.

* Balance resources (memory, disk space, network traffic) across multiple nodes for scalability.
* Distribute entities and data across many nodes in the cluster
* Location transparency: Interact by logical identifier versus physical location which can change over time.
* Automatic relocation on failure (rebalancing)


Shards are distributed in shard regions. 

Persistent actor entity - stateful, long-lived, addressable entity.



Sharding + Replication


akka-cluster-sharding enables running at most one instance of a give actor in the cluster at any point in time
acting as a consistency boundary. That's exactly what we want our shards to be. 
Each process starts knowing its shard name (`alpha`, `betta` or `gamma`)
It uses the given shard name as a cluster role and starts sharding on that role. Moreover, it will allocate only one instance of
`DeviceShadowEntity` per node as we have one to one mapping between shard in entity.

On each node that belongs to lets;s say `alpha` role, we allocate only one sharded entity. 
Combination of host ip and port is used for shard/entity name, therefore it guarantees 
that each node runs only one instance of sharded entity.

Each node starts http api, therefore next q to answer being how we decide where the incoming requests should be routed? For that
porpuse we have `RingMaster` actor. As processes join the cluster, they take ownership for token ranges on a hash ring based on shard name (role).
`RingMaster` is deployed as cluster singleton, holds the hash redirects all incoming requests to a particular shard region(role form above).


Therefore, if we have the following set of shards: 
`alpha`, `betta`, `gamma` then we also have 3 independently running shard regions, each of which knows nothing about each other. 
Each shard region is being used only inside a particular shard and each sharded entity become a replica of the shard. 
In other words, each shard becomes its own distributed system as each sharded entity inside the shard runs its own independent replicator



Next question to address:
 When we add a new shard, say betta, in an operational cluster of 2 alpha nodes ([alpha -> 127.0.0.1-2551,127.0.0.2-2551]), 
 we need to transfer data, that from now on is associated with betta [alpha -> 127.0.0.1-2551,127.0.0.2-2551, betta -> 127.0.0.10-2551]
 
 

## Docker compose setup  

The docker-compose2.yml by default runs: 
 * 2 replicas for alpha
 * 1 replica for betta
 * 1 replica for gamma

Later you can scale up and down the number of replicas for these shards.
All replicas that have the same shard name should be responsible to the same range of keys and should have independent replicators on it.     

#### A sequence of docker commands ####
  
  * First of all we need to export these env vars
    
    `export SEED_DNS=master`

    `export HTTP_PORT=9000`
    
    `export AKKA_PORT=2551`
    
    `export HOST=192.168.178.10`  or  `export HOST=192.168.178.11`
    
    `export TZ=UTC`

    `export DM=config`
    
  * Build and publish the image `sbt -DSHARD=docker docker`
  
  * Start one seed node and one worker node `docker-compose -f docker-compose2.yml up -d`
     
  * Scale up the number of workers `docker-compose -f docker-compose2.yml scale alpha=3`
   
  * Scale down the number of workers `docker-compose -f docker-compose2.yml scale alpha=2`
  
  * Stop all processes `docker-compose -f docker-compose2.yml stop`
  
  * Delete all processes `docker-compose -f docker-compose2.yml rm`
    
  * Now you can build the image again

### Network

sudo ifconfig lo0 127.0.0.2 add

sudo ifconfig lo0 127.0.0.3 add


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

  `http GET 192.168.178.10:9000/view`

  `http GET 192.168.178.10:9000/view1`

  `http GET 192.168.178.10:9000/members`

  `http://192.168.178.10:9000/rng`

### Cluster members

   `http GET :9000/cluster/members`
    

### Jvm metrics 
  `curl --no-buffer :9000/metrics`

### Ping device
  
  `http GET :9000/device/1`  

### Query shard regions

`http 127.0.0.1:9000/shards`


### Query processes  
 
lsof -i :2551 | grep LISTEN | awk '{print $2}'

kill -stop <pid>

kill -suspend <pid>

lsof -i :8558 | grep LISTEN | awk '{print $2}'


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

https://doc.akka.io/docs/akka/2.5.25/typed/cluster-sharding.html

https://doc.akka.io/docs/akka/current/distributed-data.html

https://groups.google.com/forum/#!topic/akka-user/MO-4XhwhAN0

https://www.digitalocean.com/community/tutorials/how-to-provision-and-manage-remote-docker-hosts-with-docker-machine-on-ubuntu-16-04

https://github.com/mckeeh3/akka-java-cluster-kubernetes/tree/d714ad5651ee4dc84464d1995be3c2d3ae9ca684

https://doc.akka.io/docs/akka-management/current/cluster-http-management.html

ExternalShardAllocation https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html?_ga=2.193469741.1478281344.1585435561-801666185.1515340543#external-shard-allocation

https://www.lightbend.com/blog/how-to-distribute-application-state-with-akka-cluster-part-1-getting-started


### CPU considerations for Java applications running in Docker and Kubernetes

https://www.lightbend.com/blog/cpu-considerations-for-java-applications-running-in-docker-and-kubernetes


### Akka CoordinatedShutdown

https://medium.com/bestmile/orchestrating-startup-and-shutdown-in-scala-f7ad2644835a

### Docker  

https://docs.docker.com/compose/compose-file/#resources

https://github.com/chbatey/docker-jvm-akka/blob/master/docker-compose.yml

https://dzone.com/articles/docker-container-resource-management-cpu-ram-and-i

http://www.batey.info/docker-jvm-k8s.html

https://www.lightbend.com/blog/cpu-considerations-for-java-applications-running-in-docker-and-kubernetes?utm_campaign=Oktopost-COLL-Build+Stateful+Cloud+Native+Applications&utm_content=Oktopost-twitter&utm_medium=social&utm_source=twitter


### Why Streamee

Goals: lossless deployment, back-pressure throughout the whole req/res pipeline.

We have 2 nodes Alice and Bob. Both host actors and accept incoming requests via rest api. When we shutdown either of nodes we could lose request.
In particular, if we shutdown Bob while actors on Bob haven't yet replied to actors on Alice, the requests that have started on Alice won't be 
completed. The solution being is that leaving nodes should drain both incoming and outgoing channels. 
Draining of incoming(local) requests channel (what CoordinatedShutdown gives you) is not enough.

`Streamee` models long-running processes and those processes are suitably hooked into CS to no longer accept new reqs and delay the shutdown until all accepted 
req have been processed(across whole pipeline).  

Why akka-cluster-sharding is not enough? The good part is that it guarantees that if a command riches a shard region and the target sharded entity 
gets rebalanced or crushed, the command will be buffered and re-routed to the entity once it's available again somewhere else. The problem being that 
by the time the entity is available again, the caller may already get ask timeout, so we lose the response.

It buffers during rebalancing which takes place when a node fails or  

### Akka-cluster-sharding links 

https://manuel.bernhardt.io/2018/02/26/tour-akka-cluster-cluster-sharding/
https://www.youtube.com/watch?v=SrPubnOKJcQ
https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html?_ga=2.193469741.1478281344.1585435561-801666185.1515340543#external-shard-allocation


### To DO

Instead of storing all actorRef in HashRingState I could store only one Leaseholder per shard and interact with it.



### SBT

sbt '; set javaOptions += "-Dconfig.resource=cluster-application.conf" ; run’

sbt -J-Xms512M -J-XX:+PrintCommandLineFlags -J-XshowSettings
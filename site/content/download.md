+++
title = "Downloads"
menu = "main"
weight = 150
+++

## Binaries

The preferred way of deploying Eclipse Hono&trade; is by means of using the pre-built Docker images provided
on [Docker Hub](https://hub.docker.com/u/eclipsehono/).

The most convenient way to both pull the images and start corresponding containers is by means of
[deploying a *stack* using Docker's *Swarm Mode*](https://docs.docker.com/engine/reference/commandline/stack_deploy/). You can find an example stack definition using the published Hono images here:

* [Docker Compose file](docker-compose.yml)

After downloading the file, simply run the following from the command line (assuming that you have Docker Engine running in Swarm mode):

~~~sh
$ docker stack deploy -c docker-compose.yml hono
~~~

### Older Versions

* [0.5-M4 Docker Compose file](docker-compose-0.5-M4.yml)
* [0.5-M3 Docker Compose file](docker-compose-0.5-M3.yml)
* [0.5-M2 Docker Compose file](docker-compose-0.5-M2.yml)

## Source Code

The source code can be cloned (using [Git](https://git-scm.com/)) or downloaded from the [Eclipse Hono GitHub repository](https://github.com/eclipse/hono)
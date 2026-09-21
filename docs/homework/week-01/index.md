# 环境检查

```

grass@yilong15pro:~$ java --version
openjdk 25.0.4.1 2026-08-18 LTS
OpenJDK Runtime Environment Zulu25.36+205-CA (build 25.0.4.1+1-LTS)
OpenJDK 64-Bit Server VM Zulu25.36+205-CA (build 25.0.4.1+1-LTS, mixed mode, sharing)

grass@yilong15pro:~$ mvn --version
Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
Maven home: /opt/maven
Java version: 25.0.4.1, vendor: Azul Systems, Inc., runtime: /usr/lib/jvm/zulu-25-amd64
Default locale: zh_CN, platform encoding: UTF-8
OS name: "linux", version: "7.0.0-31-generic", arch: "amd64", family: "unix"

grass@yilong15pro:~$ git --version
git version 2.53.0

grass@yilong15pro:~$ sudo docker version
Client: Docker Engine - Community
 Version:           29.8.1
 API version:       1.56
 Go version:        go1.26.8
 Git commit:        4a63305
 Built:             Tue Sep 15 16:25:42 2026
 OS/Arch:           linux/amd64
 Context:           default

Server: Docker Engine - Community
 Engine:
  Version:          29.8.1
  API version:      1.56 (minimum version 1.40)
  Go version:       go1.26.8
  Git commit:       464cd50
  Built:            Tue Sep 15 16:25:42 2026
  OS/Arch:          linux/amd64
  Experimental:     false
 containerd:
  Version:          v2.3.5
  GitCommit:        1294c24a7da8e5a793ed378161673abe94118892
 runc:
  Version:          1.5.1
  GitCommit:        v1.5.1-0-g8f2685a4
 docker-init:
  Version:          0.19.0
  GitCommit:        de40ad0

grass@yilong15pro:~$ docker compose version
Docker Compose version v5.5.1

```

# 概念回答

## 什么是微服务架构?

微服务架构是将单一应用程序开发为一套小服务的方法

## 微服务和单体架构的主要区别是什么?

单体架构采用单一部署单元，耦合度较高，因此扩展性差，技术栈单一，但是对于小型项目来说结构简单，开发速度快。而微服务架构可以将各个服务分布式部署，耦合度较低，可扩展性强，可采用多种多样的技术栈，适合团队协作开发，较适合大型项目的开发

## 为什么本课程先实现单体系统，再逐步拆分为微服务?

用于学习、考察微服务的设计

## 为什么作业需要提供可重复运行的测试或测验脚本?

用于学习、考察微服务的配置和部署

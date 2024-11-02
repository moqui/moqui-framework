#!/bin/sh

java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -cp . MoquiStart conf=conf/MoquiDevConf.xml
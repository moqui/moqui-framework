## Welcome to Moqui Framework

[![license](https://img.shields.io/badge/license-CC0%201.0%20Universal-blue.svg)](https://github.com/moqui/moqui-framework/blob/master/LICENSE.md)
[![build](https://travis-ci.org/moqui/moqui-framework.svg)](https://travis-ci.org/moqui/moqui-framework)
[![release](https://img.shields.io/github/release/moqui/moqui-framework.svg)](https://github.com/moqui/moqui-framework/releases)
[![commits since release](http://img.shields.io/github/commits-since/moqui/moqui-framework/v3.0.0.svg)](https://github.com/moqui/moqui-framework/commits/master)
[![downloads](https://img.shields.io/github/downloads/moqui/moqui-framework/total.svg)](https://github.com/moqui/moqui-framework/releases)
[![downloads](https://img.shields.io/github/downloads/moqui/moqui-framework/v3.0.0/total.svg)](https://github.com/moqui/moqui-framework/releases/tag/v3.0.0)

[![Discourse Forum](https://img.shields.io/badge/moqui%20forum-discourse-blue.svg)](https://forum.moqui.org)
[![Google Group](https://img.shields.io/badge/google%20group-moqui-blue.svg)](https://groups.google.com/d/forum/moqui)
[![LinkedIn Group](https://img.shields.io/badge/linked%20in%20group-moqui-blue.svg)](https://www.linkedin.com/groups/4640689)
[![Gitter Chat at https://gitter.im/moqui/moqui-framework](https://badges.gitter.im/moqui/moqui-framework.svg)](https://gitter.im/moqui/moqui-framework?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Stack Overflow](https://img.shields.io/badge/stack%20overflow-moqui-blue.svg)](http://stackoverflow.com/questions/tagged/moqui)

## Installation procedure

1. ```git clone moqui-framework <app-name>```
2. ```cd <app-name> + git clone moqui-runtime runtime```
3. ```cd runtime ``` checkout setup comp to provide basic features
4. provide runnable conf file and upload it to runtime/conf
5. run ```gradlew load``` to initialize database
6. prepare docker scripts for generating Docker images

When using with PostgreSQL databases, create database using scripts in build-utils/db directory


## Moqui related info

For information about community infrastructure for code, discussions, support, etc see the Community Guide:

<https://www.moqui.org/docs/moqui/Community+Guide>

For details about running and deploying Moqui see:

<https://www.moqui.org/docs/framework/Run+and+Deploy>

Note that a runtime directory is required for Moqui Framework to run, but is not included in the source repository. The
Gradle get component, load, and run tasks will automatically add the default runtime (from the moqui-runtime repository).

For information about the current and near future status of Moqui Framework
see the [ReleaseNotes.md](https://github.com/moqui/moqui-framework/blob/master/ReleaseNotes.md) file.

For an overview of features see:

<https://www.moqui.org/docs/framework/Framework+Features>

Get started with Moqui development quickly using the Tutorial at:

<https://www.moqui.org/docs/framework/Quick+Tutorial>

For comprehensive documentation of Moqui Framework see the wiki based documentation on moqui.org (*running on Moqui HiveMind*):
 
<https://www.moqui.org/m/docs/framework>

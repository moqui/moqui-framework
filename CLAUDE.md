# Moqui AI Integration — Claude Code Guide

## Session start
At the start of every session, before doing anything:
1. Read this file
2. Fetch active issues:
   gh issue view 1 --repo hotwax/moqui-ai
   gh issue view 3 --repo hotwax/moqui-ai
3. Confirm current step and file to touch
4. Wait for explicit go-ahead

## Project layout
- Framework source: framework/src/main/java/org/moqui/
- Framework impl:   framework/src/main/groovy/org/moqui/impl/
- Default config:   framework/src/main/resources/MoquiDefaultConf.xml
- Components:       runtime/component/

## What we are building
Adding ec.ai facade to Moqui following the same pattern as ec.elastic.
Reference files to study before making any change:
- framework/src/main/java/org/moqui/context/ElasticFacade.java
- framework/src/main/groovy/org/moqui/impl/context/ElasticFacadeImpl.groovy
- framework/src/main/java/org/moqui/context/ExecutionContext.java
- framework/src/main/groovy/org/moqui/impl/context/ExecutionContextImpl.java
- framework/src/main/groovy/org/moqui/impl/context/ExecutionContextFactoryImpl.groovy

## Rules — always follow these
1. Never modify any runtime/component/ files
2. Never run gradle build unless explicitly asked
3. One step at a time — do not proceed until asked
4. After every file change, show diff before saving
5. Do not read files outside framework/src/ unless told to

## Build system
- Java 21, Gradle
- To compile: ./gradlew compileGroovy
- Do NOT run the server or load data unless asked

## Git
- Feature branch: feature/ec-ai-facade
- Push to: origin (patelanil/moqui-framework)

## LangChain4j
- Version: 1.8.0
- Modules: langchain4j + langchain4j-open-ai
- Java 21 compatible ✅

## Issue tracker
- https://github.com/hotwax/moqui-ai (issues only, no code)

## Current step
All six steps complete. ec.ai facade is fully implemented.

## Completed steps
Step 1 — DONE. MoquiDefaultConf.xml config.
Step 2 — DONE. AiFacade.java interface.
Step 3 — DONE. AiFacadeImpl.groovy implementation.
Step 4 — DONE. Wiring into ExecutionContext.
Step 5 — DONE. AiFacadeTests smoke test.
Step 6 — DONE. README and generateStructured test.
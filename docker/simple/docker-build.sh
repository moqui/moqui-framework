#! /bin/bash

unzip ../../moqui.war
cp -R ../../runtime .

docker build -t moqui .

rm -Rf META-INF WEB-INF execlib
rm *.class
rm -Rf runtime

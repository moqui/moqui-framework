# No memory or other JVM options specified here so that the standard JAVA_TOOL_OPTIONS env var may be used (command line args trump JAVA_TOOL_OPTIONS)
# For example: export JAVA_TOOL_OPTIONS="-Xmx1024m -Xms1024m"
# Note that in Java 8 if no max heap size is specified it will default to 1/4 system memory
#
# The port specified here is the default for the AWS ElasticBeanstalk Java SE image
# see: https://docs.aws.amazon.com/elasticbeanstalk/latest/dg/java-se-procfile.html
#
web: java -cp . MoquiStart port=5000 conf=conf/MoquiProductionConf.xml run-es

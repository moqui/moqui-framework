@Echo off

Echo Stopping LDAP environment

pushd ldap
docker-compose -f ldap.yml -p moqui.ldap down
popd

pause
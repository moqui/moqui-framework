@Echo off

Echo Mounting LDAP environment

pushd ..\..\ldap
docker-compose -f ldap.yml -p moqui.ldap up -d
popd

pause
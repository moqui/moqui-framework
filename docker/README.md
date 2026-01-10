# Moqui On Docker

This directory contains everything needed to deploy moqui on docker. To do so
follow these instructions:

- Choose a docker compose file in `docker/`. For example to deploy moqui on
  postgres you can choose moqui-postgres-compose.yml.
- Find and download a suitable JDBC driver for the target database, download its
  jar file and place it in `runtime/lib`.
- Generate moqui war file `./gradlew build`
- Get into docker folder `cd docker`
- Build chosen compose file, e.g. `./build-compose-up.sh moqui-postgres-compose.yml`

This last step would build the "moqui" image and deploy all services. You can
confirm by accessing the system on http://localhost

For a more secure and complete deployment, it is recommended to carefully review
the compose files and adjust as needed, including changing credentials and other
settings such as setting the host names, configuring for letsencrypt, etc ...

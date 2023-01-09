#!/bin/bash

# This is a simple script to do a rotating backup of PostgreSQL (default once per day, retain 30 days)
# For a complete backup solution these backup files would be copied to a remote site, potentially with a different retention pattern

# Database info
user="moqui"
host="localhost"
db_name="moqui"
# Other options
# a full path from root should be used for backup_path or there will be issues running via crontab
backup_path="/opt/pgbackups"
date=$(date +"%Y%m%d")
backup_file=$backup_path/$db_name-$date.sql.gz

# for password for cron job one option is to use a .pgpass file in home directory, see: https://www.postgresql.org/docs/current/libpq-pgpass.html
# each line in .pgpass should be like: hostname:port:database:username:password
# for example: localhost:5432:moqui:moqui:CHANGEME
# note that ~/.pgpass must have u=rw (0600) permission or less (or psql, pg_dump, etc will refuse to use it)

# Remove file for same day if exists
if [ -e $backup_file ]; then rm $backup_file; fi
# Set default file permissions
umask 177
# Dump database into SQL file
pg_dump -h $host -p 5432 -U $user -w $db_name | gzip > $backup_file
# Delete files older than 30 days
find $backup_path/*.sql.gz -mtime +30 -exec rm {} \;

# update cloned test instance database using backup file from production/main database
# docker stop moqui-test
# dropdb -h localhost -p 5432 -U moqui -w moqui-test
# createdb -h localhost -p 5432 -U moqui -w moqui-test
# gunzip < $backup_file | psql -h localhost -p 5432 -U moqui -w moqui-test
# docker start moqui-test

# example for crontab (safe edit using: 'crontab -e'), each day at midnight: 00 00 * * * /opt/moqui/postgres_backup.sh


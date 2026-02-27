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
# Remove all files not within 7 days, most recent per month for 6 months, or most recent of the year
echo "removing:"
ls "$backup_path"/moqui-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9].sql.gz | awk -v now_epoch="$(date +%s)" '
{
  date_string = substr($0, index($0,"-")+1, 8)
  command = "date -d \"" date_string "\" +%s"
  command | getline file_epoch
  close(command)

  files[NR] = $0
  file_epoch_by_name[$0] = file_epoch

  year_month = substr(date_string,1,6)
  year_only  = substr(date_string,1,4)

  age_in_months = int((now_epoch - file_epoch) / 2592000)

  if (age_in_months < 6 &&
      (!(year_month in newest_month_epoch) ||
        file_epoch > newest_month_epoch[year_month])) {
    newest_month_epoch[year_month] = file_epoch
    newest_month_file[year_month]  = $0
  }

  if (!(year_only in newest_year_epoch) ||
       file_epoch > newest_year_epoch[year_only]) {
    newest_year_epoch[year_only] = file_epoch
    newest_year_file[year_only]  = $0
  }
}
END {
  for (i in files) {
    file_name = files[i]
    file_epoch = file_epoch_by_name[file_name]

    date_string = substr(file_name, index(file_name,"-")+1, 8)
    year_month  = substr(date_string,1,6)
    year_only   = substr(date_string,1,4)

    if (now_epoch - file_epoch <= 7*86400) continue
    if (file_name == newest_month_file[year_month]) continue
    if (file_name == newest_year_file[year_only]) continue

    printf "%s\0", file_name
  }
}' |
xargs -0 --no-run-if-empty rm -v

# update cloned test instance database using backup file from production/main database
# docker stop moqui-test
# dropdb -h localhost -p 5432 -U moqui -w moqui-test
# createdb -h localhost -p 5432 -U moqui -w moqui-test
# gunzip < $backup_file | psql -h localhost -p 5432 -U moqui -w moqui-test
# docker start moqui-test

# example for crontab (safe edit using: 'crontab -e'), each day at midnight: 00 00 * * * /opt/moqui/postgres_backup.sh

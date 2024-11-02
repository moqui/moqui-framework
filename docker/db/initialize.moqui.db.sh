

function create_user() {
    local user=$1
	local pwd=$2
	echo "  Creating user '$user'"
	psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
	    CREATE USER $user WITH PASSWORD '$pwd';
EOSQL
}

function create_database() {
	local database=$1
    local user=$2
	echo "  Creating database with name '$database' under user '$user'"
	psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
	    CREATE DATABASE $database;
	    GRANT ALL PRIVILEGES ON DATABASE $database TO $user;
EOSQL
}

function restore_data() {
    local database=$1
    echo "  Restoring '$database' from fixed backup '/dumps/moqui-dump.sql'"
    psql $database < /dumps/moqui-dump.sql
}

# create_user postgres postgres
create_user moqui postgres

# create database and restore
create_database moqui_db moqui
restore_data moqui_db

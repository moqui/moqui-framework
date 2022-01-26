

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
	local user=$2
    echo "  Restoring from backup '$database', granting access to '$user'"
    psql $database < /dumps/moqui-dump.sql

	psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname $database <<-EOSQL
			GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO $user;
			GRANT ALL PRIVILEGES ON ALL SEQUENCES  IN SCHEMA public TO $user;
	EOSQL
}

# create_user postgres postgres
create_user moqui postgres

# create database and restore
create_database moqui_db moqui
restore_data moqui_db moqui

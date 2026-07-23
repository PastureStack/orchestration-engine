#!/bin/bash
set -x -e

LIQUIBASE_HOME=${LIQUIBASE_HOME:-~/.local/liquibase}
DB=${DB:-cattle}
DRIVER_GROUP_ID=${DRIVER_GROUP_ID:-com.mysql}
DRIVER_ARTIFACT_ID=${DRIVER_ARTIFACT_ID:-mysql-connector-j}
DRIVER_VERSION=${DRIVER_VERSION:-9.7.0}
DRIVER_CLASS=${DRIVER_CLASS:-com.mysql.cj.jdbc.Driver}
JDBC_SCHEME=${JDBC_SCHEME:-mysql}

function prep_driver_jar(){
    if [ -n "${DRIVER:-}" ]; then
        echo "$DRIVER"
    else
        local driver_jar
        driver_jar=$(find "$HOME/.m2" -name "${DRIVER_ARTIFACT_ID}-${DRIVER_VERSION}.jar" -print -quit)
        if [ -z "$driver_jar" ]; then
            mvn -q -DgroupId="$DRIVER_GROUP_ID" -DartifactId="$DRIVER_ARTIFACT_ID" -Dversion="$DRIVER_VERSION" dependency:get
            driver_jar=$(find "$HOME/.m2" -name "${DRIVER_ARTIFACT_ID}-${DRIVER_VERSION}.jar" -print -quit)
        fi
        if [ -n "$driver_jar" ]; then
            echo "$driver_jar"
        else
            # Couldn't install driver
            return 1
        fi
    fi
}

if [ -e dump.xml ]; then
    mv dump.xml dump-$(date '+%s').xml
fi

DRIVER_JAR=$(prep_driver_jar)

JAVA_OPTS="-Duser.name=rancher" $LIQUIBASE_HOME/liquibase --classpath="$DRIVER_JAR"  \
    --driver="$DRIVER_CLASS" \
    --changeLogFile=dump.xml \
    --url="jdbc:${JDBC_SCHEME}://localhost:3306/${DB}_base" \
    --username="$DB" \
    --password="$DB" \
     diffChangeLog \
    --referenceUrl="jdbc:${JDBC_SCHEME}://localhost:3306/$DB" \
    --referenceUsername="$DB" \
    --referencePassword="$DB"

sed -i -E \
    -e '2r header.xml' \
    -e 's/id="[0-9]+-([0-9]+)/id="dump\1/g' \
    -e 's/BIGINT\(19\)/BIGINT/g' \
    -e 's/BIT\(1\)/BIT/g' \
    -e 's/INT\(10\)/INT/g' \
    dump.xml

cat dump.xml

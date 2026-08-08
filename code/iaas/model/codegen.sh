#!/bin/bash
set -e

cd $(dirname $0)
pushd ../../..
mvn -DskipTests -am -pl code/framework/jooq install
popd
#mvn -Djooq.version=3.2.0 -Dexec.classpathScope=test -Dexec.mainClass=org.jooq.util.GenerationTool -Dexec.arguments="/codegen.xml" package exec:java
mvn -DskipTests -Dexec.classpathScope=test -Dexec.mainClass=org.jooq.codegen.GenerationTool -Dexec.arguments="$(pwd)/src/test/resources/codegen.xml" exec:java

# Keep generated JPA annotations on Jakarta Persistence for the JDK25 runtime.
# Keep generated warning suppressions narrow enough that new JDK warnings are
# visible under the JDK25 -Werror gate instead of being hidden by codegen defaults.
find src/main/java -name '*.java' -print0 | xargs -0 sed -i 's/@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })/@SuppressWarnings("unchecked")/g'
perl -0pi -e 's/\n@SuppressWarnings\("unchecked"\)\npublic class Indexes /\npublic class Indexes /' src/main/java/io/cattle/platform/core/model/Indexes.java
perl -0pi -e 's/public class Indexes \{\n/public class Indexes {\n\n    @SafeVarargs\n    private static OrderField<?>[] orderFields(OrderField<?>... fields) {\n        return fields;\n    }\n\n/' src/main/java/io/cattle/platform/core/model/Indexes.java
perl -0pi -e 's/new OrderField\[\] \{ ([^{}]*) \}/orderFields($1)/g' src/main/java/io/cattle/platform/core/model/Indexes.java
perl -0pi -e 's/\n@SuppressWarnings\("unchecked"\)\npublic class Keys /\npublic class Keys /' src/main/java/io/cattle/platform/core/model/Keys.java
perl -0pi -e 's/public class Keys \{\n/public class Keys {\n\n    @SafeVarargs\n    private static <R extends org.jooq.Record> TableField<R, ?>[] tableFields(TableField<R, ?>... fields) {\n        return fields;\n    }\n\n/' src/main/java/io/cattle/platform/core/model/Keys.java
perl -0pi -e 's/new TableField\[\] \{ ([^{}]*) \}/tableFields($1)/g' src/main/java/io/cattle/platform/core/model/Keys.java
find src/main/java/io/cattle/platform/core/model/tables -maxdepth 1 -name '*Table.java' -print0 | xargs -0 perl -0pi -e 's/\n@SuppressWarnings\("unchecked"\)\npublic class /\npublic class /'
find src/main/java/io/cattle/platform/core/model/tables -maxdepth 1 -name '*Table.java' ! -name 'DatabasechangelogTable.java' ! -name 'DatabasechangeloglockTable.java' -print0 | xargs -0 perl -0pi -e 's/return \(Identity<[^>]+Record, Long>\) super\.getIdentity\(\);/return Internal.createIdentity(this, ID);/g'
find src/main/java/io/cattle/platform/core/model/tables/records -name '*Record.java' -print0 | xargs -0 perl -0pi -e 's/\n@SuppressWarnings\("unchecked"\)\n@Entity\n/\n@Entity\n/'
find src/main/java/io/cattle/platform/core/model/tables/records -name '*Record.java' -print0 | xargs -0 perl -0pi -e 'sub column { my $name = shift; $name =~ s/([a-z0-9])([A-Z])/$1_$2/g; return uc $name; } if (my ($table) = /super\((\w+Table\.\w+)\);/) { s/return \(Record1\) super\.key\(\);/return super.key().into($table.ID);/g; s/public Map<String,Object> get(\w+)\(\) \{\n\s*return \(Map<String,Object>\) get\(\d+\);\n\s*\}/"public Map<String,Object> get$1() {\n        return get($table." . column($1) . ");\n    }"/eg; }'

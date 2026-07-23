set -eu
test -f src/main/java/sample/Slugger.java
test ! -e src/main/java/sample/LegacySlugger.java
rm -rf .verify-out
mkdir -p .verify-out
javac --release 21 -d .verify-out $(find src/main/java src/test/java -name '*.java' | sort)
java -cp .verify-out sample.ArticleServiceTest

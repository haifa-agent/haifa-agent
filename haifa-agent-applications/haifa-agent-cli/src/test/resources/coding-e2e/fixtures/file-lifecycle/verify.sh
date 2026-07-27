set -eu
test -f src/main/java/sample/Slugger.java
test ! -e src/main/java/sample/LegacySlugger.java
rm -rf .verify-out
mkdir -p .verify-out
javac --release 21 -d .verify-out \
  src/main/java/sample/ArticleService.java \
  src/main/java/sample/Slugger.java \
  src/test/java/sample/ArticleServiceTest.java
java -cp .verify-out sample.ArticleServiceTest

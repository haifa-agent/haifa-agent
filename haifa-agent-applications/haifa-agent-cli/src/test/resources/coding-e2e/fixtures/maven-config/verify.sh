set -eu
grep -q '<sourceDirectory>src/main/java</sourceDirectory>' pom.xml
rm -rf .verify-out
mkdir -p .verify-out
javac --release 21 -d .verify-out $(find src/main/java -name '*.java' | sort)

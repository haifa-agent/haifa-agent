#!/bin/sh
set -eu
umask 077

if [ "$(uname -s):$(uname -m)" != Linux:x86_64 ]; then
    echo 'This Coding Agent installer requires Linux x86_64.' >&2
    exit 1
fi
if ! getconf GNU_LIBC_VERSION >/dev/null 2>&1; then
    echo 'This Coding Agent package requires glibc. Use Ubuntu 24.04 or WSL2 Ubuntu 24.04.' >&2
    exit 1
fi

root="$HOME/.local/share/haifa-agent"
bin="$HOME/.local/bin"
mkdir -p "$root/packages"
stage=$(mktemp -d "$root/.install-XXXXXXXX")
trap 'rm -rf -- "$stage"' EXIT
trap 'exit 1' HUP INT TERM

echo 'Downloading Coding Agent @TAG@ for Linux x64...'
curl -fLSs --retry 2 -o "$stage/@ARCHIVE@" '@DOWNLOAD@'
printf '%s  %s\n' '@SHA256@' '@ARCHIVE@' | (cd "$stage" && sha256sum -c --quiet)
tar -xzf "$stage/@ARCHIVE@" -C "$stage"
package="$root/packages/@PACKAGE_ID@"
if [ ! -d "$package" ]; then
    mv -nT -- "$stage/haifa-coding-agent-@TAG@" "$package" || [ -d "$package" ]
fi
"$package/jre/bin/java" -cp "$package/lib/*" io.haifa.agent.cli.distribution.InstallCodingAgent \
    "$package" "$bin"

# Bash reads different files for interactive and login shells.
profile="$HOME/.profile"
if [ -f "$HOME/.bash_profile" ]; then
    profile="$HOME/.bash_profile"
elif [ -f "$HOME/.bash_login" ]; then
    profile="$HOME/.bash_login"
fi
for file in "$HOME/.bashrc" "$profile"; do
    if ! grep -Fqs '# Haifa Agent PATH' "$file"; then
        cat >> "$file" <<'HAIFA_PATH'

# Haifa Agent PATH
case ":$PATH:" in
    ":$HOME/.local/bin:"*) ;;
    *) export PATH="$HOME/.local/bin:$PATH" ;;
esac
HAIFA_PATH
    fi
done
echo 'Installed Coding Agent @TAG@. Run haifa from your project directory.'

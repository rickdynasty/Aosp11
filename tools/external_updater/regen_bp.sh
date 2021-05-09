#!/bin/bash
#
# Copyright (C) 2020 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# This script is used by external_updater to replace a package.
# It can also be invoked directly.  It is used in two ways:
# (1) in a .../external/* rust directory with .bp and Cargo.toml;
#     cargo2android.py must be in PATH
# (2) in a tmp new directory with .bp and Cargo.toml,
#     and $1 equals to the rust Android source tree root,
#     and $2 equals to the rust sub-directory path name under external.

set -e

# Wrapper around cargo2android.
C2A_WRAPPER="/google/bin/releases/android-rust/cargo2android/sandbox.par"

function main() {
  check_files $*
  update_files_with_cargo_pkg_vars
  # Save Cargo.lock if it existed before this update.
  [ ! -f Cargo.lock ] || mv Cargo.lock Cargo.lock.saved
  echo "Updating Android.bp: $C2A_WRAPPER -- $FLAGS"
  $C2A_WRAPPER -- $FLAGS
  copy_cargo_out_files $*
  rm -rf target.tmp cargo.out Cargo.lock
  # Restore Cargo.lock if it existed before this update.
  [ ! -f Cargo.lock.saved ] || mv Cargo.lock.saved Cargo.lock
}

function abort() {
  echo "$1" >&2
  exit 1
}

function check_files() {
  if [ "$1" == "" ]; then
    EXTERNAL_DIR=`pwd`
  else
    EXTERNAL_DIR="$2"  # e.g. rust/crates/bytes
  fi
  [ -f "$C2A_WRAPPER" ] || abort "ERROR: cannot find $C2A_WRAPPER"
  LINE1=`head -1 Android.bp || abort "ERROR: cannot find Android.bp"`
  if [[ ! "$LINE1" =~ ^.*cargo2android.py.*$ ]]; then
    echo 'Android.bp header does not contain "cargo2android.py"; skip regen_bp'
    exit 0
  fi
  FLAGS=`echo "$LINE1" | sed -e 's:^.*cargo2android.py ::;s:\.$::'`
  [ -f Cargo.toml ] || abort "ERROR: cannot find ./Cargo.toml."
}

function copy_cargo_out_files() {
  if [ -d $2/out ]; then
    # copy files generated by cargo build to out directory
    PKGNAME=`basename $2`
    for f in $2/out/*
    do
      OUTF=`basename $f`
      SRC=`ls ./target.tmp/*/debug/build/$PKGNAME-*/out/$OUTF ||
           ls ./target.tmp/debug/build/$PKGNAME-*/out/$OUTF || true`
      if [ "$SRC" != "" ]; then
        echo "Copying $SRC to out/$OUTF"
        mkdir -p out
        cp $SRC out/$OUTF
      fi
    done
  fi
}

function update_files_with_cargo_pkg_vars() {
  FILES=`grep -r -l --include \*.rs \
    --exclude-dir .git --exclude build.rs \
    --exclude-dir target.tmp --exclude-dir target \
    -E 'env!\("CARGO_PKG_(NAME|VERSION|AUTHORS|DESCRIPTION)"\)' * || true`
  if [ "$FILES" != "" ]; then
    printf "INFO: to update FILES: %s\n" "`echo ${FILES} | paste -s -d' '`"
    # Find in ./Cargo.toml the 'name', 'version', 'authors', 'description'
    # strings and use them to replace env!("CARGO_PKG_*") in $FILES.
    grep_cargo_key_values
    update_files
  fi
}

function grep_one_key_value()
{
  # Grep the first key $1 in Cargo.toml and return its value.
  grep "^$1 = " Cargo.toml | head -1 | sed -e "s:^$1 = ::" \
    || abort "ERROR: Cannot find '$1' in ./Cargo.toml"
}

function grep_cargo_key_values()
{
  NAME=`grep_one_key_value name`
  VERSION=`grep_one_key_value version`
  AUTHORS=`grep_one_key_value authors`
  DESCRIPTION=`grep_one_key_value description`
  if [ "$DESCRIPTION" == "\"\"\"" ]; then
    # Old Cargo.toml description format, found only in the 'shlex' crate.
    DESCRIPTION=`printf '"%s-%s"' "$NAME" "$VERSION"`
    printf "WARNING: use %s for its CARGO_PKG_DESCRIPTION." "$DESCRIPTION"
  fi
  # CARGO_PKG_AUTHORS uses ':' as the separator.
  AUTHORS="$AUTHORS.join(\":\")"
}

function build_sed_cmd()
{
  # Replace '\' with '\\' to keep escape sequence in the sed command.
  # NAME and VERSION are simple stings without escape sequence.
  s1=`printf "$1" "NAME" "$NAME"`
  s2=`printf "$1" "VERSION" "$VERSION"`
  s3=`printf "$1" "AUTHORS" "${AUTHORS//\\\\/\\\\\\\\}"`
  s4=`printf "$1" "DESCRIPTION" "${DESCRIPTION//\\\\/\\\\\\\\}"`
  echo "$s1;$s2;$s3;$s4"
}

function update_files()
{
  # Replace option_env!("...") with Some("...")
  # Replace env!("...") with string literal "..."
  # Do not replace run-time std::env::var("....") with
  #   (Ok("...".to_string()) as std::result::Result<...>)
  local cmd=`build_sed_cmd 's%%option_env!("CARGO_PKG_%s")%%Some(%s)%%g'`
  cmd="$cmd;"`build_sed_cmd 's%%env!("CARGO_PKG_%s")%%%s%%g'`
  sed -i -e "$cmd" $FILES
}

main $*
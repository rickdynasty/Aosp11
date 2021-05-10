#!/bin/bash -ex

# This script updates the prebuilt test_framework-sdkextension.jar, which is
# required when the "new APIs" added change, or the framework jar changes
# for other reasons.

function gettop() {
    local p=$(pwd)
    while [[ ! -e "${p}/build/envsetup.sh" ]]; do
        p="${p}/.."
    done
    echo $(readlink -f $p)
}

if [[ -z "$OUT" ]]; then
    echo "lunch first"
    exit 1
fi

dir=$(dirname $(readlink -f $BASH_SOURCE))
bp="${dir}/../framework/Android.bp"

if ! test -e $bp; then
    echo $bp does not exist
    exit 1
elif test -e "${bp}.bak"; then
    echo "skipping ${bp} modification because ${bp}.bak exists"
    continue
fi
cp $bp "${bp}.bak"
sed -i -e 's|":framework-sdkextensions-sources"|":framework-sdkextensions-sources",":test_framework-sdkextensions-sources"|' $bp

$(gettop)/build/soong/soong_ui.bash --make-mode framework-sdkextensions

mv "${bp}.bak" $bp ; touch $bp
cp "${OUT}/apex/com.android.sdkext/javalib/framework-sdkextensions.jar" "${dir}/test_framework-sdkextensions.jar"

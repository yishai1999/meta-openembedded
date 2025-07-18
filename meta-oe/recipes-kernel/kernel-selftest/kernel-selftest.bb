SUMMARY = "Kernel selftest for Linux"
DESCRIPTION = "Kernel selftest for Linux"
LICENSE = "GPL-2.0-only"

LIC_FILES_CHKSUM = "file://COPYING;md5=bbea815ee2795b2f4230826c0c6b8814"

DEPENDS = "rsync-native llvm-native"

S = "${UNPACKDIR}"

# for musl libc
SRC_URI:append:libc-musl = "\
                      file://userfaultfd.patch \
                      "
SRC_URI += "file://run-ptest \
            file://COPYING \
            file://0001-selftests-timers-Fix-clock_adjtime-for-newer-32-bit-.patch \
            "

# now we just test bpf and vm
# we will append other kernel selftest in the future
# bpf was added in 4.10 with: https://github.com/torvalds/linux/commit/5aa5bd14c5f8660c64ceedf14a549781be47e53d
# if you have older kernel than that you need to remove it from PACKAGECONFIG
PACKAGECONFIG ??= "firmware"
# bpf needs working clang compiler anyway
PACKAGECONFIG:append:toolchain-clang:x86-64 = " bpf"
PACKAGECONFIG:remove:x86 = "bpf"
PACKAGECONFIG:remove:arm = "bpf vm"
# host ptrace.h is used to compile BPF target but mips ptrace.h is needed
# progs/loop1.c:21:9: error: incomplete definition of type 'struct user_pt_regs'
# m = PT_REGS_RC(ctx);
# vm tests need libhugetlbfs starting 5.8+ (https://lkml.org/lkml/2020/4/22/1654)
PACKAGECONFIG:remove:qemumips = "bpf vm"

# riscv does not support libhugetlbfs yet
PACKAGECONFIG:remove:riscv64 = "bpf vm"
PACKAGECONFIG:remove:riscv32 = "bpf vm"

PACKAGECONFIG[bpf] = ",,elfutils elfutils-native libcap libcap-ng rsync-native python3-docutils-native,"
PACKAGECONFIG[firmware] = ",,libcap, bash"
PACKAGECONFIG[vm] = ",,libcap libhugetlbfs,libgcc bash"

do_patch[depends] += "virtual/kernel:do_shared_workdir"
do_compile[depends] += "virtual/kernel:do_install"

inherit linux-kernel-base module-base kernel-arch ptest siteinfo

DEBUG_PREFIX_MAP:remove = "-fcanon-prefix-map"

TEST_LIST = "\
    ${@bb.utils.filter('PACKAGECONFIG', 'bpf firmware vm', d)} \
    rtc \
    ptp \
    timers \
"
EXTRA_OEMAKE = '\
    CROSS_COMPILE=${TARGET_PREFIX} \
    ARCH=${ARCH} \
    CC="${CC} ${DEBUG_PREFIX_MAP}" \
    AR="${AR}" \
    LD="${LD}" \
    CLANG="clang -fno-stack-protector -target ${TARGET_ARCH} ${TOOLCHAIN_OPTIONS} -isystem ${S} -D__WORDSIZE=\'64\' -Wno-error=unused-command-line-argument" \
    DESTDIR="${D}" \
    V=1 \
'
EXTRA_OEMAKE:append:toolchain-clang = "\
    LLVM=1 CONFIG_CC_IS_GCC= CONFIG_CC_IS_CLANG=y CONFIG_CC_IMPLICIT_FALLTHROUGH= \
    HOSTCC="clang -unwindlib=libgcc -rtlib=libgcc -stdlib=libstdc++ ${BUILD_CFLAGS} ${BUILD_LDFLAGS} -Wno-error=unused-command-line-argument" \
    HOSTLD="clang ${BUILD_LDFLAGS} -unwindlib=libgcc -rtlib=libgcc -stdlib=libstdc++" \
"

KERNEL_SELFTEST_SRC ?= "Makefile \
                        include \
                        kernel \
                        lib \
                        tools \
                        scripts \
                        arch \
                        LICENSES \
"
do_compile() {
    if [ ${@bb.utils.contains('PACKAGECONFIG', 'bpf', 'True', 'False', d)} = 'True' ]; then
    if [ ${@bb.utils.contains('DEPENDS', 'clang-native', 'True', 'False', d)} = 'False' ]; then
        bbwarn "clang >= 6.0 with bpf support is needed with kernel 4.18+ so
either install it and add it to HOSTTOOLS, or add clang-native from meta-clang to dependency"
    fi
    fi
    mkdir -p ${S}/include/config ${S}/bits
    install -Dm 0644 ${STAGING_KERNEL_BUILDDIR}/.config ${S}/include/config/auto.conf
    if [ "${SITEINFO_BITS}" != "32" ]; then
        for f in long-double endianness floatn struct_rwlock; do
            cp ${RECIPE_SYSROOT}${includedir}/bits/$f-64.h ${S}/bits/$f-32.h
        done
    fi
    oe_runmake -C ${S} headers
    sed -i -e 's|^all: docs|all:|' ${S}/tools/testing/selftests/bpf/Makefile
    sed -i -e '/mrecord-mcount/d' ${S}/Makefile
    sed -i -e '/Wno-alloc-size-larger-than/d' ${S}/Makefile
    sed -i -e '/Wno-alloc-size-larger-than/d' ${S}/scripts/Makefile.*
    oe_runmake -C ${S}/tools/testing/selftests TARGETS="${TEST_LIST}"
}

do_install() {
    oe_runmake -C ${S}/tools/testing/selftests INSTALL_PATH=${D}/usr/kernel-selftest TARGETS="${TEST_LIST}" install
    if [ -e ${D}/usr/kernel-selftest/bpf/test_offload.py ]; then
        sed -i -e '1s,#!.*python3,#! /usr/bin/env python3,' ${D}/usr/kernel-selftest/bpf/test_offload.py
    fi
    chown root:root  -R ${D}/usr/kernel-selftest
}

do_patch[prefuncs] += "copy_kselftest_source_from_kernel remove_unrelated"
python copy_kselftest_source_from_kernel() {
    sources = (d.getVar("KERNEL_SELFTEST_SRC") or "").split()
    src_dir = d.getVar("STAGING_KERNEL_DIR")
    dest_dir = d.getVar("S")
    bb.utils.mkdirhier(dest_dir)
    for s in sources:
        src = oe.path.join(src_dir, s)
        dest = oe.path.join(dest_dir, s)
        if os.path.isdir(src):
            oe.path.copytree(src, dest)
        else:
            bb.utils.copyfile(src, dest)
}

remove_unrelated() {
    if ${@bb.utils.contains('PACKAGECONFIG','bpf','true','false',d)} ; then
        test -f ${S}/tools/testing/selftests/bpf/Makefile && \
            sed -i -e 's/test_pkt_access.*$/\\/' \
                   -e 's/test_pkt_md_access.*$/\\/' \
                   -e 's/sockmap_verdict_prog.*$/\\/' \
                   ${S}/tools/testing/selftests/bpf/Makefile || \
            bberror "Your kernel is probably older than 4.10 and doesn't have tools/testing/selftests/bpf/Makefile file from https://github.com/torvalds/linux/commit/5aa5bd14c5f8660c64ceedf14a549781be47e53d, disable bpf PACKAGECONFIG"
    fi
}

do_configure[dirs] = "${S}"

PACKAGE_ARCH = "${MACHINE_ARCH}"

INHIBIT_PACKAGE_DEBUG_SPLIT = "1"
FILES:${PN} += "/usr/kernel-selftest"

RDEPENDS:${PN} += "python3 perl perl-module-io-handle"

INSANE_SKIP:${PN} += "libdir"

# A few of the selftests set compile flags that trip up the "ldflags" and
# "already-stripped" QA checks.  As this is mainly a testing package and
# not really meant for user level execution, disable these two checks.
INSANE_SKIP:${PN} += "ldflags"
INSANE_SKIP:${PN} += "already-stripped"

SECURITY_CFLAGS = ""
COMPATIBLE_HOST:libc-musl = 'null'

# It has native clang/llvm dependency, poky distro is reluctant to include them as deps
# this helps with world builds on AB
EXCLUDE_FROM_WORLD = "1"

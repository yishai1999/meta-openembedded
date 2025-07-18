SUMMARY = "Crypto and TLS for C++11"
HOMEPAGE = "https://botan.randombit.net"
LICENSE = "BSD-2-Clause"
LIC_FILES_CHKSUM = "file://license.txt;md5=3f911cecfc74a2d9f1ead9a07bd92a6e"
SECTION = "libs"

SRC_URI = "https://botan.randombit.net/releases/Botan-${PV}.tar.xz"
SRC_URI[sha256sum] = "fc0620463461caaea8e60f06711d7e437a3ad1eebd6de4ac29c14bbd901ccd1b"

S = "${UNPACKDIR}/Botan-${PV}"

inherit python3native siteinfo lib_package

CPU ?= "${TARGET_ARCH}"
CPU:x86 = "x86_32"
CPU:armv7a = "armv7"
CPU:armv7ve = "armv7"

do_configure() {
	python3 ${S}/configure.py \
	--prefix="${exec_prefix}" \
	--libdir="${libdir}" \
	--cpu="${CPU}" \
	--cc-bin="${CXX}" \
	--cxxflags="${CXXFLAGS}" \
	--ldflags="${LDFLAGS}" \
	--with-endian=${@oe.utils.conditional('SITEINFO_ENDIANNESS', 'le', 'little', 'big', d)} \
	${@bb.utils.contains("TUNE_FEATURES","neon","","--disable-neon",d)} \
	--with-sysroot-dir=${STAGING_DIR_HOST} \
	--with-build-dir="${B}" \
	--optimize-for-size \
	--with-stack-protector \
	--enable-shared-library \
	--with-python-versions=3 \
	${EXTRA_OECONF}
}

do_compile() {
	oe_runmake
}
do_install() {
	oe_runmake DESTDIR=${D} install
	sed -i -e 's|${WORKDIR}|<scrubbed>|g' ${D}${includedir}/botan-3/botan/build.h
	
	# Add botan binary and test tool
	install -d ${D}${bindir}
	install -d ${D}${datadir}/${PN}/tests/data
	install -m 0755 ${B}/botan-test  ${D}${bindir}
	cp -R --no-dereference --preserve=mode,links -v ${B}/src/tests/data/*  ${D}${datadir}/${PN}/tests/data/
}

PACKAGES += "${PN}-test ${PN}-python3"

FILES:${PN}-python3 = "${libdir}/python3"

RDEPENDS:${PN}-python3 += "python3"
RDEPENDS:${PN}-bin  += "${PN}"
RDEPENDS:${PN}-test += "${PN}"
FILES:${PN}:remove   = "${bindir}/*"
FILES:${PN}-bin:remove   = "${bindir}/*"
FILES:${PN}-bin   = "${bindir}/botan"
FILES:${PN}-test = "${bindir}/botan-test  ${datadir}/${PN}/tests/data"
COMPATIBLE_HOST:riscv32 = "null"

BBCLASSEXTEND = "native nativesdk"

#!/system/bin/sh
# Root helper of the MatePad Linux installer, run through su:
#   sh matepad.sh probe
#   sh matepad.sh release-card DEV          unmount everything on the card
#   sh matepad.sh wipe-card DEV             zero the partition tables at both ends
#   sh matepad.sh reread DEV                reread the partition table, wait for DEV p1
#   sh matepad.sh populate PART WLAN BT     copy this tablet's files into the new root
#   sh matepad.sh configure PART            account, language, time zone (stdin)
#   sh matepad.sh stage-check               verify /cache/linux70 (SHA256SUMS)
# Partitions of the internal storage are only read here; the app writes the
# boot partitions through /cache/linux70/boot-linux.sh.
set -e
PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
ROOT=/mnt/matepad-linux-root
STAGE=/cache/linux70

say() { echo "$*"; }
die() { echo "error: $*" >&2; exit 1; }

part() {
	for base in /dev/block/by-name /dev/block/bootdevice/by-name; do
		[ -e "$base/$1" ] && { echo "$base/$1"; return 0; }
	done
	return 1
}

first_part() {	# mmcblk0 -> mmcblk0p1
	echo "${1}p1"
}

wlan_mac() {
	f=/proc/wifi_built_in/mac_addr_hw	# Huawei: the 6 address bytes, not text
	if [ -r $f ]; then
		od -An -tx1 -N6 $f | tr -s ' ' | sed 's/^ //; s/ $//; s/ /:/g' | tr a-f A-F
		return 0
	fi
	[ -r /sys/class/net/wlan0/address ] && head -n 1 /sys/class/net/wlan0/address
}

bt_mac() {
	v=$(settings get secure bluetooth_address 2>/dev/null || true)
	case "$v" in ??:??:??:??:??:??) echo "$v"; return 0 ;; esac
	for p in persist.service.bdroid.bdaddr ro.boot.btmacaddr ro.bt.bdaddr_path; do
		v=$(getprop "$p")
		case "$v" in
		??:??:??:??:??:??) echo "$v"; return 0 ;;
		/*) [ -r "$v" ] && { head -n 1 "$v"; return 0; } ;;
		esac
	done
	for f in /proc/bt_built_in/mac_addr_hw /data/vendor/bluedroid/bdaddr /mnt/vendor/persist/bluetooth/bdaddr; do
		[ -r "$f" ] && { head -n 1 "$f" | tr -d ' \r'; return 0; }
	done
}

probe() {
	say "uid=$(id -u)"
	say "model=$(getprop ro.product.model)"
	say "device=$(getprop ro.product.device)"
	say "board=$(getprop ro.product.board)"
	say "platform=$(getprop ro.board.platform)"
	say "android=$(getprop ro.build.version.release)"
	for p in boot ramdisk dtbo cache; do
		d=$(part $p) && say "part_$p=$d"
	done
	for b in /sys/block/mmcblk*; do
		[ -e "$b/device/type" ] || continue
		[ "$(cat $b/device/type)" = SD ] || continue
		n=${b##*/}
		say "sd=/dev/block/$n"
		say "sd_sectors=$(cat $b/size)"
		say "sd_name=$(cat $b/device/name 2>/dev/null)"
		if [ -e "/dev/block/${n}p1" ]; then
			say "sd_p1_label=$(blkid "/dev/block/${n}p1" 2>/dev/null | sed -n 's/.* LABEL="\([^"]*\)".*/\1/p')"
		fi
		break
	done
	say "wlan_mac=$(wlan_mac)"
	say "bt_mac=$(bt_mac)"
	say "cache_free_kb=$(df -k /cache | awk 'NR==2 {print $4}')"
	if [ -f $STAGE/SHA256SUMS ] && (cd $STAGE && sha256sum -c SHA256SUMS >/dev/null 2>&1); then
		say "staged=1"
	else
		say "staged=0"
	fi
}

release_card() {
	dev=$1
	grep "^$dev" /proc/mounts | while read -r src mnt rest; do
		umount "$mnt" 2>/dev/null || umount -l "$mnt" || true
	done
	# vold's view of the card (public volume) goes too
	sm list-volumes public 2>/dev/null | while read -r id rest; do
		case "$id" in public:*) sm unmount "$id" 2>/dev/null || true ;; esac
	done
	grep -q "^$dev" /proc/mounts && die "$dev is still mounted"
	true
}

wipe_card() {
	dev=$1
	sectors=$(cat /sys/block/${dev##*/}/size)
	dd if=/dev/zero of="$dev" bs=1048576 count=4 conv=fsync 2>/dev/null
	dd if=/dev/zero of="$dev" bs=512 seek=$((sectors - 2048)) count=2048 conv=fsync 2>/dev/null
	say "wiped the partition tables of $dev ($sectors sectors)"
}

reread() {
	dev=$1
	p1=$(first_part "$dev")
	blockdev --rereadpt "$dev" || die "the kernel keeps the old partition table of $dev (still in use?)"
	i=0
	while [ ! -b "$p1" ] && [ $i -lt 20 ]; do
		sleep 0.5
		i=$((i + 1))
	done
	if [ ! -b "$p1" ]; then	# no uevent node: make it from sysfs
		mm=$(cat /sys/block/${dev##*/}/${p1##*/}/dev) || die "no partition 1 on $dev"
		mknod "$p1" b "${mm%:*}" "${mm#*:}"
	fi
	say "p1=$p1 bytes=$(blockdev --getsize64 "$p1")"
}

copy_list() {	# copy_list LIST: files and dir/ entries, same paths under $ROOT
	grep -v '^#' "$1" | grep . | while read -r p; do
		case "$p" in
		/android-properties*) src=/dev/__properties__${p#/android-properties} ;;
		*) src=$p ;;
		esac
		dst=$ROOT${p%/}
		if [ -d "$src" ]; then
			mkdir -p "$dst" && cp -a "${src%/}/." "$dst/"
		elif [ -e "$src" ] || [ -L "$src" ]; then
			mkdir -p "${dst%/*}" && cp -a "$src" "$dst"
		else
			echo "missing $src" >&2
		fi
	done
}

populate() {
	p1=$1 wlan=$2 bt=$3
	mkdir -p $ROOT
	mount -t ext4 -o rw,noatime "$p1" $ROOT || die "cannot mount $p1"
	trap 'umount $ROOT 2>/dev/null || umount -l $ROOT; rmdir $ROOT 2>/dev/null' EXIT
	DF=$ROOT/usr/share/matepad/device-files
	B=$ROOT/usr/lib/matepad/linux-boot
	[ -f $DF/manifest.txt ] && [ -f $DF/firmware.txt ] || die "the image has no $DF (not a MatePad image?)"
	[ -f $B/SHA256SUMS ] || die "the image has no boot bundle in $B"
	(cd $B && sha256sum -c SHA256SUMS >/dev/null) || die "boot bundle in the image is damaged"

	say "step=device-files"
	copy_list $DF/manifest.txt
	say "step=firmware"
	FW=$ROOT/usr/lib/firmware/qcom/sm8250/huawei/dby-w09
	mkdir -p $FW
	grep -v '^#' $DF/firmware.txt | grep . | while read -r f; do
		for d in /vendor/firmware /vendor/firmware_mnt/image /firmware/image; do
			[ -e "$d/$f" ] && { cp "$d/$f" $FW/; continue 2; }
		done
		echo "missing firmware $f" >&2
	done

	say "step=identity"
	mkdir -p $ROOT/etc/matepad
	[ "$wlan" != - ] && echo "$wlan" > $ROOT/etc/matepad/wlan-mac	# "-": unknown
	[ "$bt" != - ] && echo "$bt" > $ROOT/etc/matepad/bt-mac

	say "step=stock-images"
	H=$ROOT/usr/local/share/matepad/harmonyos-images
	mkdir -p $H
	for p in boot ramdisk dtbo; do
		dd if="$(part $p)" of=$H/$p.img bs=1048576 2>/dev/null
	done
	(cd $H && sha256sum boot.img ramdisk.img dtbo.img > SHA256SUMS)

	say "step=boot-bundle"
	rm -rf $STAGE && mkdir -p $STAGE/bundle
	cp $B/* $STAGE/bundle/
	(cd $STAGE/bundle && sha256sum -c SHA256SUMS >/dev/null) || die "copying the boot bundle to /cache failed"
	sync
	say "free_kb=$(df -k $ROOT | awk 'NR==2 {print $4}')"
	say "step=done"
}

configure() {	# configure PART, settings from stdin: KEY=value lines, PASSWORD last
	p1=$1
	FULLNAME= USERNAME= HOSTNAME= LOCALE= KEYBOARD= TIMEZONE= AUTOLOGIN= PASSWORD=
	while IFS= read -r line; do
		case "$line" in
		FULLNAME=*) FULLNAME=${line#FULLNAME=} ;;
		USERNAME=*) USERNAME=${line#USERNAME=} ;;
		HOSTNAME=*) HOSTNAME=${line#HOSTNAME=} ;;
		LOCALE=*) LOCALE=${line#LOCALE=} ;;
		KEYBOARD=*) KEYBOARD=${line#KEYBOARD=} ;;
		TIMEZONE=*) TIMEZONE=${line#TIMEZONE=} ;;
		AUTOLOGIN=*) AUTOLOGIN=${line#AUTOLOGIN=} ;;
		PASSWORD=*) PASSWORD=${line#PASSWORD=} ;;
		esac
	done
	[ -n "$USERNAME" ] && [ -n "$PASSWORD" ] || die "no user name or password"
	mkdir -p $ROOT
	mount -t ext4 -o rw "$p1" $ROOT || die "cannot mount $p1"
	mount -o bind /dev $ROOT/dev && mount -t proc proc $ROOT/proc && mount -t sysfs sysfs $ROOT/sys ||
		die "cannot prepare the chroot"
	trap 'cd /; for d in sys proc dev; do umount $ROOT/$d 2>/dev/null || umount -l $ROOT/$d; done;
		umount $ROOT 2>/dev/null || umount -l $ROOT; rmdir $ROOT 2>/dev/null' EXIT
	# Ubuntu's own tools inside the new root (HarmonyOS's kernel runs them)
	C() { chroot $ROOT /usr/bin/env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin HOME=/root LANG=C.UTF-8 "$@"; }

	say "user $USERNAME"
	groups=
	for g in adm cdrom sudo dip plugdev lpadmin sambashare users; do
		C getent group $g >/dev/null && groups="$groups${groups:+,}$g"
	done
	C useradd -m -s /bin/bash -c "$FULLNAME" -G "$groups" "$USERNAME" || die "cannot create the user $USERNAME"
	printf '%s:%s\n' "$USERNAME" "$PASSWORD" | C chpasswd || die "cannot set the password"
	PASSWORD=

	say "computer name $HOSTNAME"
	echo "$HOSTNAME" > $ROOT/etc/hostname
	C sed -i "s/^127\.0\.1\.1[[:space:]].*/127.0.1.1\t$HOSTNAME/" /etc/hosts

	say "time zone $TIMEZONE"
	[ -e "$ROOT/usr/share/zoneinfo/$TIMEZONE" ] || die "unknown time zone $TIMEZONE"
	ln -sf "/usr/share/zoneinfo/$TIMEZONE" $ROOT/etc/localtime
	echo "$TIMEZONE" > $ROOT/etc/timezone

	say "language $LOCALE"
	if ! C locale -a 2>/dev/null | grep -qix "${LOCALE%%.*}.utf8"; then
		C locale-gen "$LOCALE" >/dev/null || die "cannot generate the locale $LOCALE"
	fi
	C update-locale LANG="$LOCALE"
	home=/home/$USERNAME
	mkdir -p $ROOT$home/.config
	printf '[Formats]\nLANG=%s\n' "$LOCALE" > $ROOT$home/.config/plasma-localerc

	say "keyboard $KEYBOARD"
	variants=$(echo "$KEYBOARD" | sed 's/[^,]//g')	# one empty variant per layout
	opts=
	[ -n "$variants" ] && opts=grp:alt_shift_toggle
	printf 'XKBMODEL="pc105"\nXKBLAYOUT="%s"\nXKBVARIANT="%s"\nXKBOPTIONS="%s"\nBACKSPACE="guess"\n' \
		"$KEYBOARD" "$variants" "$opts" > $ROOT/etc/default/keyboard
	printf '[Layout]\nLayoutList=%s\nVariantList=%s\nOptions=%s\nResetOldOptions=%s\nUse=true\n' \
		"$KEYBOARD" "$variants" "$opts" "$([ -n "$opts" ] && echo true || echo false)" > $ROOT$home/.config/kxkbrc
	C chown -R "$USERNAME:$USERNAME" "$home/.config"

	if [ "$AUTOLOGIN" = 1 ]; then
		say "automatic login for $USERNAME"
		mkdir -p $ROOT/etc/sddm.conf.d
		printf '[Autologin]\nUser=%s\nSession=plasmawayland\n' "$USERNAME" > $ROOT/etc/sddm.conf.d/90-matepad-autologin.conf
	fi

	say "Kubuntu's first-boot setup is not needed any more"
	C systemctl disable oem-config.service oem-config.target >/dev/null 2>&1 || true
	C systemctl set-default graphical.target >/dev/null 2>&1 || die "cannot set the default target"
	sync
	say "configured"
}

case "$1" in
probe) probe ;;
configure) configure "$2" ;;
release-card) release_card "$2" ;;
wipe-card) wipe_card "$2" ;;
reread) reread "$2" ;;
populate) populate "$2" "$3" "$4" ;;
stage-check) cd $STAGE && sha256sum -c SHA256SUMS ;;
*) die "unknown command $1" ;;
esac

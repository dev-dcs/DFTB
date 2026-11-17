// DFTB native helper. JNI surface for ca.devdcs.backup.core.nativebridge.DftbNative.
//
// Every function here is a straight system-call wrapper. They exist because the Java APIs either
// do not expose the information (SELinux contexts, mode bits, block-device sizes) or report it
// inaccurately on bind-mounted / FUSE paths (StatFs rounds, and cannot be pointed at a raw mount
// point the app has no DocumentFile for).

#include <jni.h>
#include <android/log.h>

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <linux/fs.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <unistd.h>

#define LOG_TAG "DFTB/native"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char* kFsStatsClass = "ca/devdcs/backup/core/nativebridge/NativeFsStats";
constexpr const char* kFileStatClass = "ca/devdcs/backup/core/nativebridge/NativeFileStat";

/// RAII wrapper so every early return releases the UTF chars.
class ScopedUtf {
 public:
  ScopedUtf(JNIEnv* env, jstring s) : env_(env), s_(s), c_(s ? env->GetStringUTFChars(s, nullptr) : nullptr) {}
  ~ScopedUtf() { if (c_) env_->ReleaseStringUTFChars(s_, c_); }
  const char* get() const { return c_; }
 private:
  JNIEnv* env_;
  jstring s_;
  const char* c_;
};

jobject NewFsStats(JNIEnv* env, const struct statvfs& st) {
  jclass cls = env->FindClass(kFsStatsClass);
  if (!cls) return nullptr;
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJJJJ)V");
  if (!ctor) return nullptr;
  const auto total = static_cast<jlong>(st.f_blocks) * static_cast<jlong>(st.f_frsize);
  const auto freeB = static_cast<jlong>(st.f_bfree) * static_cast<jlong>(st.f_frsize);
  const auto avail = static_cast<jlong>(st.f_bavail) * static_cast<jlong>(st.f_frsize);
  return env->NewObject(cls, ctor, total, freeB, avail,
                        static_cast<jlong>(st.f_frsize),
                        static_cast<jlong>(st.f_files),
                        static_cast<jlong>(st.f_ffree));
}

}  // namespace

extern "C" {

/// statvfs(path). Returns null and sets errno-derived message via lastErrno on failure.
JNIEXPORT jobject JNICALL
Java_ca_devdcs_backup_core_nativebridge_DftbNative_statVfs(JNIEnv* env, jobject, jstring jpath) {
  ScopedUtf path(env, jpath);
  if (!path.get()) return nullptr;
  struct statvfs st {};
  if (statvfs(path.get(), &st) != 0) {
    LOGW("statvfs(%s) failed: %s", path.get(), strerror(errno));
    return nullptr;
  }
  return NewFsStats(env, st);
}

/// lstat(path) + SELinux context, without following symlinks.
JNIEXPORT jobject JNICALL
Java_ca_devdcs_backup_core_nativebridge_DftbNative_lstat(JNIEnv* env, jobject, jstring jpath) {
  ScopedUtf path(env, jpath);
  if (!path.get()) return nullptr;
  struct stat st {};
  if (lstat(path.get(), &st) != 0) {
    return nullptr;
  }
  char ctx[256] = {0};
  ssize_t n = lgetxattr(path.get(), "security.selinux", ctx, sizeof(ctx) - 1);
  jstring jctx = env->NewStringUTF(n > 0 ? ctx : "");

  jclass cls = env->FindClass(kFileStatClass);
  if (!cls) return nullptr;
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(IIIJJJJLjava/lang/String;)V");
  if (!ctor) return nullptr;
  return env->NewObject(cls, ctor,
                        static_cast<jint>(st.st_mode),
                        static_cast<jint>(st.st_uid),
                        static_cast<jint>(st.st_gid),
                        static_cast<jlong>(st.st_size),
                        static_cast<jlong>(st.st_mtim.tv_sec) * 1000LL + st.st_mtim.tv_nsec / 1000000LL,
                        static_cast<jlong>(st.st_ino),
                        static_cast<jlong>(st.st_dev),
                        jctx);
}

/// Size in bytes of a block device (BLKGETSIZE64), or -1. Requires read access to the node.
JNIEXPORT jlong JNICALL
Java_ca_devdcs_backup_core_nativebridge_DftbNative_blockDeviceSize(JNIEnv* env, jobject, jstring jpath) {
  ScopedUtf path(env, jpath);
  if (!path.get()) return -1;
  int fd = open(path.get(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) return -1;
  uint64_t size = 0;
  int rc = ioctl(fd, BLKGETSIZE64, &size);
  close(fd);
  return rc == 0 ? static_cast<jlong>(size) : -1;
}

/// fsync + fdatasync a file by path so a completed archive is durable before we report success.
JNIEXPORT jboolean JNICALL
Java_ca_devdcs_backup_core_nativebridge_DftbNative_fsyncPath(JNIEnv* env, jobject, jstring jpath) {
  ScopedUtf path(env, jpath);
  if (!path.get()) return JNI_FALSE;
  int fd = open(path.get(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) return JNI_FALSE;
  int rc = fsync(fd);
  close(fd);
  return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

/// access(path, mode) with POSIX flags; lets Kotlin test X_OK on staged binaries.
JNIEXPORT jboolean JNICALL
Java_ca_devdcs_backup_core_nativebridge_DftbNative_access(JNIEnv* env, jobject, jstring jpath, jint mode) {
  ScopedUtf path(env, jpath);
  if (!path.get()) return JNI_FALSE;
  return access(path.get(), mode) == 0 ? JNI_TRUE : JNI_FALSE;
}

/// chmod(path, mode); used when staging CLI tools into /data/local/tmp or the app's bin dir.
JNIEXPORT jboolean JNICALL
Java_ca_devdcs_backup_core_nativebridge_DftbNative_chmod(JNIEnv* env, jobject, jstring jpath, jint mode) {
  ScopedUtf path(env, jpath);
  if (!path.get()) return JNI_FALSE;
  return chmod(path.get(), static_cast<mode_t>(mode)) == 0 ? JNI_TRUE : JNI_FALSE;
}

/// Process identity, for diagnostics (uid 0 = root shell context, 2000 = shell/Shizuku).
JNIEXPORT jint JNICALL
Java_ca_devdcs_backup_core_nativebridge_DftbNative_currentUid(JNIEnv*, jobject) {
  return static_cast<jint>(getuid());
}

JNIEXPORT jstring JNICALL
Java_ca_devdcs_backup_core_nativebridge_DftbNative_libraryVersion(JNIEnv* env, jobject) {
  return env->NewStringUTF("dftb_native/1");
}

JNIEXPORT jstring JNICALL
Java_ca_devdcs_backup_core_nativebridge_DftbNative_lastErrorMessage(JNIEnv* env, jobject) {
  return env->NewStringUTF(strerror(errno));
}

}  // extern "C"

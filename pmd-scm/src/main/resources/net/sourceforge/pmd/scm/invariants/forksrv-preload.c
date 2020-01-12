/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

#define _GNU_SOURCE
#ifdef NDEBUG
#undef NDEBUG
#endif

#include <unistd.h>
#include <signal.h>
#include <stddef.h>
#include <string.h>
#include <stdio.h>
#include <assert.h>
#include <stdlib.h>
#include <ucontext.h>
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <linux/seccomp.h>
#include <linux/filter.h>
#include <linux/audit.h>
#include <linux/bpf.h>
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/time.h>
#include <sys/resource.h>
#include <sys/mman.h>

#define TO_SCM_MARK "## FORKSERVER -> SCM ##"

#if defined(__i386__)
#  define SC_NUM_REG  REG_EAX
#  define ARG_REG_1   REG_EBX
#  define ARG_REG_2   REG_ECX
#  define ARG_REG_3   REG_EDX
#  define ARG_REG_4   REG_ESI
#  define ARG_REG_5   REG_EDI
#  define ARG_REG_6   REG_EBP
#  define RET_REG_1   REG_EAX
#elif defined(__amd64__)
#  define SC_NUM_REG  REG_RAX
#  define ARG_REG_1   REG_RDI
#  define ARG_REG_2   REG_RSI
#  define ARG_REG_3   REG_RDX
#  define ARG_REG_4   REG_R10
#  define ARG_REG_5   REG_R8
#  define ARG_REG_6   REG_R9
#  define RET_REG_1   REG_RAX
#else
#  error Unknown CPU architecture
#endif

#define MARKER 0x12345678

#define MAX_BPF_OPS 128
#define MAX_INPUTS 1024

// Inspecting 6-args syscalls is not supported
static int inspected_syscalls[] = {
    SYS_open, SYS_openat, SYS_stat,
    SYS_execve, SYS_execveat,
    SYS_fork, SYS_vfork, SYS_clone,
};

struct file_id {
  dev_t dev;
  ino_t inode;
};

static struct file_id input_ids[MAX_INPUTS];
static int input_id_count;
static int fork_child_timeout;

static struct file_id get_file_id(const char *name, int force)
{
  struct stat statbuf;
  struct file_id result;

  // Bypass seccomp filter
  int ret = syscall(SYS_stat, name, &statbuf, 0, 0, 0, MARKER);

  if (ret == 0) {
    result.dev = statbuf.st_dev;
    result.inode = statbuf.st_ino;
  } else {
    fprintf(stderr, "Cannot stat: error %d\n", ret);
    result.dev = 0;
    result.inode = 0;
    if (force) {
      abort();
    }
  }

  return result;
}

// Print without buffering AND using fflush from a signal handler
static void print(int fd, const char *message)
{
  int len = 0;
  for (len = 0; message[len]; ++len); // strlen
  write(fd, message, len);
}

static void write_reply(const char *reply)
{
  // the actual reply string is communicated via stdout
  print(STDOUT_FILENO, TO_SCM_MARK);
  print(STDOUT_FILENO, reply);
  print(STDOUT_FILENO, "\n");
  // stderr reply is always empty
  print(STDERR_FILENO, TO_SCM_MARK "\n");
}

static void sigalrm_handler(int sig)
{
  abort();
}

// TODO make this function be more safe to execute from a SIGSYS handler
static void start_forkserver(void)
{
  // Do not re-enter the forkserver loop
  static int started = 0;
  if (started) {
    return;
  }
  started = 1;

  // For debug
  print(STDERR_FILENO, "Initializing fork server...\n");
  // Make JVM know the forkserver is ready
  write_reply("INIT");

  while (1) {
    // wait for command from JVM
    char ch;
    assert(read(STDIN_FILENO, &ch, 1) == 1);

    // Bypass seccomp filter
    int pid = syscall(SYS_fork, 0, 0, 0, 0, 0, MARKER);
    if (pid == 0) {
      // in child process
      struct rlimit rlim;
      rlim.rlim_cur = fork_child_timeout;
      rlim.rlim_max = fork_child_timeout;
      int ret = setrlimit(RLIMIT_CPU, &rlim);
      assert(ret == 0);

      signal(SIGALRM, sigalrm_handler);
      alarm(fork_child_timeout);

      return;
    }

    // in parent process
    int status;
    int ret = waitpid(pid, &status, 0);
    if (ret < 0) {
      perror("waitpid");
      abort();
    }
    int exit_code;
    if (WIFEXITED(status)) {
      exit_code = WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
      exit_code = WTERMSIG(status);
    } else {
      fprintf(stderr, "waitpid: unknown status %d\n", status);
      abort();
    }
    char buf[32];
    sprintf(buf, "%d", exit_code);
    write_reply(buf);
  }
}

static int is_input_name(const char *name)
{
  struct file_id id = get_file_id(name, 0);
  for (int i = 0; i < input_id_count; ++i) {
    if (input_ids[i].dev == id.dev && input_ids[i].inode == id.inode) {
      return 1;
    }
  }
  return 0;
}

static void handle_sigsys(int num, siginfo_t *si, void *arg)
{
  ucontext_t *ctx = arg;
  greg_t *gregs = ctx->uc_mcontext.gregs;
  int sc_num = gregs[SC_NUM_REG];
  switch (sc_num) {
  case SYS_open:
    if (is_input_name((const char *) gregs[ARG_REG_1])) {
      fprintf(stderr, "Opening %s, starting fork server.\n", (const char *) gregs[ARG_REG_1]);
      start_forkserver();
    }
    break;
  case SYS_openat:
    if (is_input_name((const char *) gregs[ARG_REG_2])) {
      fprintf(stderr, "Opening %s, starting fork server.\n", (const char *) gregs[ARG_REG_2]);
      start_forkserver();
    }
    break;
  case SYS_stat:
    if (is_input_name((const char *) gregs[ARG_REG_1])) {
      fprintf(stderr, "Calling stat() on %s, starting fork server\n", (const char *) gregs[ARG_REG_1]);
      start_forkserver();
    }
    break;
  case SYS_execve:
  case SYS_execveat:
    fprintf(stderr, "Process is trying to call exec(...), exiting.\n");
    abort();
    break;
  case SYS_fork:
  case SYS_vfork:
  case SYS_clone:
    fprintf(stderr, "Process is trying to spawn a thread or a subprocess, exiting.\n");
    abort();
    break;
  default:
    start_forkserver();
  }
  gregs[RET_REG_1] = syscall(sc_num,
            gregs[ARG_REG_1], gregs[ARG_REG_2], gregs[ARG_REG_3],
            gregs[ARG_REG_4], gregs[ARG_REG_5], MARKER);
}

static struct sock_filter *create_filter(int *length)
{
  const int inspected_syscall_count = sizeof(inspected_syscalls) / sizeof(inspected_syscalls[0]);
  struct sock_filter *filter = calloc(MAX_BPF_OPS, sizeof(struct sock_filter));
  int filter_length = 0;

  // Test for system call re-entry: 4-byte (BPF_W) MARKER should be set as the 5th arg (0-indexed)
  // do not intercepting 6-args syscalls, so it's OK
  filter[filter_length++] = (struct sock_filter) BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[5]));
  const int reenter_test_index = filter_length;
  filter_length += 1;
  // Load syscall number
  filter[filter_length++] = (struct sock_filter) BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
  // Decide what to do for this syscall number
  // To be filled after exit instruction indices will be known
  const int syscall_jumps_start = filter_length;
  filter_length += inspected_syscall_count;
  // if not inspected, then ALLOW
  const int allow_exit_index = filter_length;
  filter[filter_length++] = (struct sock_filter) BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
  const int trap_exit_index = filter_length;
  filter[filter_length++] = (struct sock_filter) BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
  assert(filter_length < MAX_BPF_OPS);

  // Fill re-enter test:
  filter[reenter_test_index] = (struct sock_filter) BPF_JUMP(
        BPF_JMP | BPF_JEQ | BPF_K,
        MARKER,
        allow_exit_index - (reenter_test_index + 1),
        0);
  // Fill syscall filter:
  for (int i = 0; i < inspected_syscall_count; ++i) {
    int ind = syscall_jumps_start + i;
    filter[ind] = (struct sock_filter) BPF_JUMP(
        BPF_JMP | BPF_JEQ | BPF_K,
        inspected_syscalls[i],
        trap_exit_index - (ind + 1), 0);
  }
  *length = filter_length;
  return filter;
}

static void initialize_signal_interceptor(void)
{
  int filter_length;
  struct sock_filter *filter = create_filter(&filter_length);

  struct sock_fprog program = { filter_length, filter };

  struct sigaction sig;
  memset(&sig, 0, sizeof(sig));
  sig.sa_sigaction = handle_sigsys;
  sig.sa_flags = SA_SIGINFO | SA_NODEFER;
  sigaction(SIGSYS, &sig, NULL);

  prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
  int ret = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &program);
  if (ret != 0) {
    perror("seccomp");
    abort();
  }

  free(filter);
}

static void initialize_inputs(void)
{
  char env_name[32];
  for (input_id_count = 0; input_id_count < MAX_INPUTS; ++input_id_count) {
    sprintf(env_name, "__SCM_INPUT_%d", input_id_count);
    const char *file_name = getenv(env_name);
    if (file_name == NULL) {
      break;
    } else {
      fprintf(stderr, "Fetched input name #%d: %s\n", input_id_count, file_name);
      input_ids[input_id_count] = get_file_id(file_name, 1);
    }
  }
}

static void __attribute__((constructor)) constr(void)
{
  const char *timeout_str = getenv("__SCM_TIMEOUT");
  assert(timeout_str != NULL);
  fork_child_timeout = atoi(timeout_str);

  initialize_inputs();
  initialize_signal_interceptor();
}

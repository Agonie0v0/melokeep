"""Prepare bind-mounted storage, then run MeloKeep as the configured UID/GID."""
import os
from pathlib import Path
import stat
import sys
import tempfile
from runtime_config import initialize_environment


def runtime_ids():
    current_uid, current_gid = os.getuid(), os.getgid()
    default_uid = 10001 if current_uid == 0 else current_uid
    default_gid = 10001 if current_uid == 0 else current_gid
    raw_uid = os.environ.get('PUID', '').strip()
    raw_gid = os.environ.get('PGID', '').strip()
    try:
        uid = int(raw_uid) if raw_uid else default_uid
        gid = int(raw_gid) if raw_gid else default_gid
    except ValueError:
        raise SystemExit('PUID 和 PGID 必须是数字，请填写 NAS 用户的 UID/GID（或 0 以 root 运行）。')
    if not 0 <= uid <= 2147483647 or not 0 <= gid <= 2147483647:
        raise SystemExit('PUID 和 PGID 范围无效：PUID 和 PGID 必须为 0 到 2147483647 之间的整数。')
    if current_uid != 0 and (uid, gid) != (current_uid, current_gid):
        raise SystemExit('容器已通过 user 指定运行身份，无法切换到 PUID/PGID；请移除 user 或让两者一致。')
    return uid, gid


def repair_owner(path, uid, gid):
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode):
        return
    if (info.st_uid, info.st_gid) != (uid, gid):
        os.chown(path, uid, gid, follow_symlinks=False)
    # Changing ownership alone does not restore write/search bits on restrictive
    # NAS mounts. Repair only owner permissions, keeping group/other bits intact.
    required = 0o700 if stat.S_ISDIR(info.st_mode) else 0o600
    mode = stat.S_IMODE(info.st_mode)
    if mode & required != required:
        if hasattr(os, 'chmod'):
            try:
                os.chmod(path, mode | required, follow_symlinks=False)
            except (NotImplementedError, TypeError):
                if not path.is_symlink():
                    path.chmod(mode | required)
        else:
            path.chmod(mode | required)


def check_writable(directory):
    directory.mkdir(parents=True, exist_ok=True)
    # os.access can succeed while NAS ACLs/read-only mounts still reject writes.
    with tempfile.TemporaryFile(dir=directory):
        pass


def main():
    if len(sys.argv) < 2:
        raise SystemExit('缺少要执行的启动命令参数。')
    try:
        initialize_environment(Path(__file__).resolve().parent)
    except (ValueError, OSError) as error:
        raise SystemExit(f'初始化运行环境配置失败：{error}') from error
    data = Path(os.environ.get('MELOKEEP_DATA_DIR', '/data'))
    music = Path(os.environ.get('MELOKEEP_FILE_ROOT') or os.environ.get('MELOKEEP_DOWNLOAD_DIR', '/music'))
    if os.environ.get('HOME') == '/data/home':
        os.environ['HOME'] = str(data / 'home')
    private = data / 'engine'
    uid, gid = runtime_ids()
    try:
        if os.getuid() == 0:
            for directory in (data, music):
                directory.mkdir(parents=True, exist_ok=True)
                repair_owner(directory, uid, gid)
            if private.is_symlink():
                raise SystemExit(f'{private} 不能是符号链接，请使用数据目录内的真实文件夹。')
            private.mkdir(exist_ok=True)
            # Repair application state subtrees directly; skip large user-managed media/backup trees if already owned.
            for sub in (private, data / 'go', data / 'home', data / 'telegram-bot-api'):
                if sub.exists() and not sub.is_symlink():
                    repair_owner(sub, uid, gid)
                    for parent, directories, files in os.walk(sub, followlinks=False):
                        for name in directories + files:
                            repair_owner(Path(parent) / name, uid, gid)
            for item in data.iterdir():
                if not item.is_symlink() and item.is_file():
                    repair_owner(item, uid, gid)
            repair_owner(private, uid, gid)
            private.chmod(0o700)
            if (uid, gid) != (0, 0):
                groups = []
                allow_self_update = os.environ.get('MELOKEEP_ALLOW_DOCKER_SELF_UPDATE', '1').strip().lower()
                if allow_self_update not in ('0', 'false', 'no', 'off'):
                    sock_path = Path(os.environ.get('MELOKEEP_DOCKER_SOCKET', '/var/run/docker.sock'))
                    try:
                        if sock_path.exists():
                            sock_stat = sock_path.stat()
                            if stat.S_ISSOCK(sock_stat.st_mode):
                                groups = [sock_stat.st_gid]
                    except OSError:
                        groups = []
                try:
                    os.setgroups(groups)
                except OSError:
                    try:
                        os.setgroups([])
                    except OSError:
                        pass
                os.setgid(gid)
                os.setuid(uid)
        if private.is_symlink():
            raise SystemExit(f'{private} 不能是符号链接，请使用数据目录内的真实文件夹。')
        for directory in (data, private, music):
            check_writable(directory)
    except OSError as error:
        raise SystemExit(
            f'存储目录权限检查失败（运行用户 {uid}:{gid}）：{error}。'
            '请确认 /data 和 /music 不是只读挂载，并在 NAS 共享文件夹权限中授予该用户读写权限。'
            '可通过 PUID/PGID 指定 NAS 用户；使用 user 非 root 启动时，需提前修复已有 data/engine 的所有者和权限。'
        ) from error
    os.execvp(sys.argv[1], sys.argv[1:])


if __name__ == '__main__':
    main()

package com.lily.onpremise.backup;

/**
 * 마지막 백업 뒤 앱 DB 가 바뀌었는지. 백업할 때의 변경 수({@link BackupState#changeMark()})와 지금 값을 비교한다.
 * 값이 작아졌으면 DB 가 다시 시작해 통계가 0 부터 다시 센 것이라 바뀐 것으로 본다. 모르면 바뀐 것으로 본다
 * (백업을 한 번 더 하는 쪽이 사본을 놓치는 쪽보다 낫다).
 */
public final class ChangeCounter {

    private final LocalDbStats db;

    public ChangeCounter(LocalDbStats db) {
        this.db = db;
    }

    /** 지금 변경 수. 읽지 못하면 null */
    public Long current(String app) {
        try {
            return db.changeCount(app);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean changed(Long mark, Long current) {
        return mark == null || current == null || !mark.equals(current);
    }
}

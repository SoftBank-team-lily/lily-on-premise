package com.lily.onpremise.backup;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 앱 하나의 백업 상태. 에이전트가 다시 떠도 이어 가도록 {@link BackupStore} 가 파일로 남긴다.
 *
 * @param requests     {@link RequestProfile} 168칸의 요청 합계
 * @param minutes      {@link RequestProfile} 168칸의 기록한 분 수
 * @param lastBackupAt 마지막으로 성공했거나 변경이 없어 건너뛴 시각. 성공은 덤프를 시작한 시각이다 (사본은 그 시점 데이터)
 * @param changeMark   마지막 백업 때의 DB 변경 수 ({@code ChangeCounter}). 없으면 null
 * @param recent       최근 결과, 오래된 것부터 {@link #KEEP} 개
 */
public record BackupState(long[] requests, long[] minutes, Instant lastBackupAt, Long changeMark,
                          List<BackupRecord> recent) {

    public static final int KEEP = 30;
    /** 후보 창 길이를 정할 때 보는 최근 성공 덤프 수 */
    static final int DUMPS_FOR_WINDOW = 3;

    public BackupState {
        recent = recent == null ? List.of() : List.copyOf(recent);
    }

    public static BackupState empty() {
        return new BackupState(new long[RequestProfile.SLOTS], new long[RequestProfile.SLOTS], null, null, List.of());
    }

    public RequestProfile profile() {
        return new RequestProfile(requests, minutes);
    }

    public BackupState withProfile(RequestProfile profile) {
        return new BackupState(profile.requests(), profile.minutes(), lastBackupAt, changeMark, recent);
    }

    /** 결과를 더한다. 성공과 건너뜀은 마감 시계를 새로 시작한다 */
    public BackupState withRecord(BackupRecord record) {
        List<BackupRecord> next = new ArrayList<>(recent);
        next.add(record);
        if (next.size() > KEEP) {
            next = next.subList(next.size() - KEEP, next.size());
        }
        Instant last = record.outcome() == BackupRecord.Outcome.FAILED ? lastBackupAt : record.startedAt();
        return new BackupState(requests, minutes, last, changeMark, next);
    }

    public BackupState withChangeMark(Long mark) {
        return new BackupState(requests, minutes, lastBackupAt, mark, recent);
    }

    /** 최근 성공한 덤프 3번 중 가장 오래 걸린 시간. 없으면 null */
    public Duration longestDump() {
        Duration longest = null;
        int seen = 0;
        for (int i = recent.size() - 1; i >= 0 && seen < DUMPS_FOR_WINDOW; i--) {
            BackupRecord record = recent.get(i);
            if (record.outcome() != BackupRecord.Outcome.OK) {
                continue;
            }
            seen++;
            if (longest == null || record.took().compareTo(longest) > 0) {
                longest = record.took();
            }
        }
        return longest;
    }

    /** 끝에서부터 이어진 실패 수 */
    public int failuresInRow() {
        int count = 0;
        for (int i = recent.size() - 1; i >= 0 && recent.get(i).outcome() == BackupRecord.Outcome.FAILED; i--) {
            count++;
        }
        return count;
    }
}

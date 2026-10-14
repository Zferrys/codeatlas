package com.codeatlas.common.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("PageResult — 通用分页结果")
class PageResultTest {

    @Test
    @DisplayName("全参构造器：字段精确保留（含空记录集合）")
    void constructor_shouldStoreAllFields() {
        PageResult<String> result = new PageResult<>(Collections.emptyList(), 0L, 1, 20);

        assertEquals(Collections.emptyList(), result.getRecords());
        assertEquals(0L, result.getTotal());
        assertEquals(1, result.getPage());
        assertEquals(20, result.getSize());
    }

    @Test
    @DisplayName("records 引用原样持有，不做拷贝")
    void constructor_shouldKeepRecordsReference() {
        List<String> records = Arrays.asList("a", "b", "c");
        PageResult<String> result = new PageResult<>(records, 3L, 2, 10);

        assertSame(records, result.getRecords());
        assertEquals(3L, result.getTotal());
        assertEquals(2, result.getPage());
        assertEquals(10, result.getSize());
    }

    @Test
    @DisplayName("无参构造器默认值：records=null、total=0、page=0、size=0")
    void defaultConstructor_shouldHaveZeroDefaults() {
        PageResult<Integer> result = new PageResult<>();

        assertNull(result.getRecords());
        assertEquals(0L, result.getTotal());
        assertEquals(0, result.getPage());
        assertEquals(0, result.getSize());
    }

    @Test
    @DisplayName("setter 可逐项覆盖（含大总数与高页码）")
    void setters_shouldOverrideEachField() {
        PageResult<Integer> result = new PageResult<>();
        List<Integer> records = Arrays.asList(1, 2);
        result.setRecords(records);
        result.setTotal(2_000_000_000L);
        result.setPage(99_999);
        result.setSize(500);

        assertSame(records, result.getRecords());
        assertEquals(2_000_000_000L, result.getTotal());
        assertEquals(99_999, result.getPage());
        assertEquals(500, result.getSize());
    }
}

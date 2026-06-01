package com.family.finance.repository;

import com.family.finance.domain.macro.MacroBenchmark;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * macro_benchmark 表 Mapper · v0.5 FR-70。
 *
 * <p>upsert 用 INSERT ... ON DUPLICATE KEY UPDATE(年度 cron 拉新值覆盖)。</p>
 */
@Mapper
public interface MacroBenchmarkMapper {

    @Select("SELECT year, cpi_headline AS cpiHeadline, m2_growth AS m2Growth, source, fetched_at AS fetchedAt "
            + "FROM macro_benchmark ORDER BY year")
    List<MacroBenchmark> findAll();

    @Select("SELECT year, cpi_headline AS cpiHeadline, m2_growth AS m2Growth, source, fetched_at AS fetchedAt "
            + "FROM macro_benchmark WHERE year = #{year}")
    Optional<MacroBenchmark> findByYear(@Param("year") int year);

    @Select("SELECT year, cpi_headline AS cpiHeadline, m2_growth AS m2Growth, source, fetched_at AS fetchedAt "
            + "FROM macro_benchmark WHERE year BETWEEN #{from} AND #{to} ORDER BY year")
    List<MacroBenchmark> findRange(@Param("from") int from, @Param("to") int to);

    @Update("""
            INSERT INTO macro_benchmark (year, cpi_headline, m2_growth, source)
            VALUES (#{year}, #{cpiHeadline}, #{m2Growth}, #{source})
            ON DUPLICATE KEY UPDATE
                cpi_headline = VALUES(cpi_headline),
                m2_growth    = VALUES(m2_growth),
                source       = VALUES(source)
            """)
    int upsert(MacroBenchmark b);
}

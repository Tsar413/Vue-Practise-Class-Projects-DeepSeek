package com.study.vuePractiseBackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.study.vuePractiseBackend.entity.TeachingEvaluation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeachingEvaluationMapper extends BaseMapper<TeachingEvaluation> {
}

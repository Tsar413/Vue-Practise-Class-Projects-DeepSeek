package com.study.vuePractiseBackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.study.vuePractiseBackend.entity.TeachingSectionAttachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TeachingSectionAttachmentMapper extends BaseMapper<TeachingSectionAttachment> {

    /** 某个磁盘文件是否仍被任意版本引用（历史安全的关键判断）。 */
    @Select("SELECT COUNT(*) FROM teaching_version_attachment WHERE attachment_id = #{attachmentId}")
    long countVersionRefs(@Param("attachmentId") Long attachmentId);

    /** 某个磁盘路径是否仍被任意版本引用。 */
    @Select("""
            SELECT COUNT(*) FROM teaching_version_attachment va
              JOIN teaching_attachment a ON a.id = va.attachment_id
             WHERE a.image_url = #{imageUrl}
            """)
    long countVersionRefsByUrl(@Param("imageUrl") String imageUrl);
}

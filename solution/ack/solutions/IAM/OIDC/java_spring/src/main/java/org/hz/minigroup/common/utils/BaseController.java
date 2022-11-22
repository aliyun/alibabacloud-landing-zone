package org.hz.minigroup.common.utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;

public class BaseController {

    public Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 输出json数据
     *
     * @param response
     * @param result
     */
    public void outputToJSON(HttpServletResponse response, Object result) {
        response.setContentType("application/json;charset=UTF-8");
        try {
            if (null != result) {
                // String resultString = JsonUtils.toJsonStringForWeb(result);
                String resultString = JsonUtils.toJsonStringWithDatetime(result);
                response.setContentLength(resultString.getBytes("UTF-8").length);
                response.getWriter().write(resultString);
            }
            response.flushBuffer();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * 输出text
     *
     * @param response
     * @param result
     */
    public void outputToString(HttpServletResponse response, String result) {
        response.setContentType("text/html;charset=UTF-8");
        try {
            if (null != result) {
                response.getWriter().write(result);
            }
            response.flushBuffer();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * 输出字节数组
     *
     * @param response
     * @param result
     * @param contentType
     */
    public void outputToByte(HttpServletResponse response, byte[] result, String contentType) {
        response.setContentType(contentType);
        try {
            if (null != result) {
                response.getOutputStream().write(result);
            }
            response.flushBuffer();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * 输出为文件
     *
     * @param response
     * @param result
     * @param fileName
     */
    public void outputToFile(HttpServletResponse response, String result, String fileName) {
        outputToFile(response, result, fileName, "text/html;charset=UTF-8");
    }

    /**
     * 输出为文件
     *
     * @param response
     * @param result
     * @param fileName
     * @param contentType
     */
    public void outputToFile(HttpServletResponse response, String result, String fileName, String contentType) {
        response.addHeader("Content-Disposition", "attachment;filename=" + fileName);
        response.setContentType(contentType);
        try {
            if (null != result) {
                response.getWriter().write(result);
            }
            response.flushBuffer();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

}

/**
 * @Title: OrgNumberNormDao.java
 * @Package: com.epay.xj.dao
 * @author LZG, liuzhongguochn@gmail.com 
 * Copyright (c) 2018 
 */
package com.epay.xj.dao;

import java.sql.SQLException;

/**
 * @Description:
 * @author LZG
 * @date 2018年07月03日
 */
public interface OrgNumberNormDao {

    /**
     * @Description: 近12个月申请认证的不同机构数
     * @param certNoStr 身份证号
     * @param currentDayStr 当前日期
     * @param beforeOneYearDayStr 一年前的这个日期
     * @return
     * @throws SQLException
     * @author LZG
     * @date 2018年07月03日
     */
    public String yl_card_audit_all_merdint_all_j12m_cnt(String certNoStr, String currentDayStr, String beforeOneYearDayStr);

    /**
     * @Description: 近12个月申请的不同贷款类机构数
     * @param certNoStr
     * @param currentDayStr
     * @param beforeOneYearDayStr
     * @return
     * @author LZG
     * @date 2018年07月03日
     */
    public String yl_card_audit_dk_merdint_all_j12m_cnt(String certNoStr, String currentDayStr, String beforeOneYearDayStr);

    /**
     * @Description: 近12个月申请的不同银行类机构数
     * @param certNoStr
     * @param currentDayStr
     * @param beforeOneYearDayStr
     * @return
     * @author LZG
     * @date 2018年07月03日
     */
    public String yl_card_audit_yh_merdint_all_j12m_cnt(String certNoStr, String currentDayStr, String beforeOneYearDayStr);

    /**
     * @Description: 近6个月申请认证的不同机构数
     * @param certNoStr
     * @param currentDayStr
     * @param beforeDayStr
     * @return
     * @author LZG
     * @date 2018年07月03日
     */
    public String yl_card_audit_all_merdint_all_j6m_cnt(String certNoStr, String currentDayStr, String beforeDayStr);

    /**
     * @Description: 近6个月申请的不同贷款类机构数
     * @param certNoStr
     * @param currentDayStr
     * @param beforeDayStr
     * @return
     * @author LZG
     * @date 2018年07月04日
     */
    public String yl_card_audit_dk_merdint_all_j6m_cnt(String certNoStr, String currentDayStr, String beforeDayStr);

}
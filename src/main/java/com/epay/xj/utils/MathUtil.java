/**   
 * @Title: MathUtil.java 
 * @Package: com.epay.xj.utils 
 * @author LZG, liuzhongguochn@gmail.com  
 * Copyright (c) 2018 https://onezg.cnblogs.com
 */
package com.epay.xj.utils;

import java.math.BigDecimal;

/**
 * @Description: 计算工具类(平均逾期次数统计)
 * @author LZG
 * @date 2018年07月10日
 */
public class MathUtil {

    /**
     * @Description: 除(保留两位小数,四舍五入)
     * @param number1
     * @param number2
     * @return
     * @author LZG
     * @date 2018年07月10日
     */
    public static String divide(int number1, int number2) {
        // 一家逾期机构都没有,说明这个人没有逾期,平均逾期自然是0
        if (number2 == 0)
            return "0";

        double result = (double) number1 / number2;

        if (result == 0.00)
            return "0";
        
        double value = new BigDecimal(result).setScale(2,BigDecimal.ROUND_HALF_UP).doubleValue();
        return String.valueOf(value);
    }

    // 测试
    public static void main(String[] args) {
        System.out.println(MathUtil.divide(10, 0));
        System.out.println(MathUtil.divide(9, 8));
        System.out.println(MathUtil.divide(0, 8));
        System.out.println(MathUtil.divide(1, 8));
      
    }

}

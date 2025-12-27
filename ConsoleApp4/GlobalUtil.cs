using System;
using System.IO;
public static class GlobalUtil
{
    /// <summary>
    /// 带后缀的文件名 
    /// </summary>
    /// <param name="fileName"></param>
    /// <returns></returns>
    public static string GetQrFilePath(string fileName)
    {
        string projectRoot = Path.GetFullPath(
                                   Path.Combine(AppDomain.CurrentDomain.BaseDirectory, @"..\..\..\"));
        string qrPath = Path.Combine(projectRoot, fileName);
        return qrPath;
    }
    /// <summary>
    /// 获取工作区目录 
    /// </summary>
    /// <returns></returns>
    public static string GetWorkDirPath()
    {
        string projectRoot = Path.GetFullPath(
                                  Path.Combine(AppDomain.CurrentDomain.BaseDirectory, @"..\..\..\"));
        
        return  projectRoot.Replace("\\","/");
    }
}

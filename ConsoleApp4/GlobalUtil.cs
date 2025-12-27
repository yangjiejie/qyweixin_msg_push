using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

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
    public static string GetWorkDirPath()
    {
        string projectRoot = Path.GetFullPath(
                                  Path.Combine(AppDomain.CurrentDomain.BaseDirectory, @"..\..\..\"));
        
        return  projectRoot.Replace("\\","/");
    }
}

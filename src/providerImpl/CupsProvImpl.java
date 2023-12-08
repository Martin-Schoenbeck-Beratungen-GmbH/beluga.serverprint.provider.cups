package providerImpl;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.PrintService;
import javax.print.SimpleDoc;
import javax.print.attribute.Attribute;
import javax.print.attribute.EnumSyntax;
import javax.print.attribute.HashDocAttributeSet;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.IntegerSyntax;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.TextSyntax;
import javax.print.attribute.standard.Copies;
import javax.print.attribute.standard.JobName;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.print.PrintUtil;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Language;

import de.lohndirekt.print.IppPrintService;
import de.lohndirekt.print.IppPrintServiceLookup;
import de.lohndirekt.print.attribute.IppAttributeName;
import de.lohndirekt.print.attribute.ipp.jobtempl.LdMediaTray;
import de.lohndirekt.print.attribute.ipp.printerdesc.supported.MediaSourceSupported;
import de.schoenbeck.serverprint.helper.EnumSubtitute;
import de.schoenbeck.serverprint.model.I_sbsp_attributename;
import de.schoenbeck.serverprint.model.I_sbsp_attributevalue;
import de.schoenbeck.serverprint.model.MPrinter;
import de.schoenbeck.serverprint.model.MPrinterAttributeName;
import de.schoenbeck.serverprint.model.MPrinterAttributeValue;
import de.schoenbeck.serverprint.model.MPrinterProvider;
import de.schoenbeck.serverprint.printProvider.AttributeLookup;
import de.schoenbeck.serverprint.printProvider.PrinterLookup;
import de.schoenbeck.serverprint.printProvider.Printrun;
import de.schoenbeck.serverprint.work.PrinterConfig;

@SuppressWarnings("rawtypes")
public class CupsProvImpl implements Printrun, PrinterLookup, AttributeLookup {

	@Override
	public List<MPrinter> getAvailablePrinters(int provider_id, String trxName) {
		MPrinterProvider prov = new MPrinterProvider(Env.getCtx(), provider_id, trxName);
//		PrintService[] services = IppPrintServiceLookup.lookupPrintServices(null, null);
		PrintService[] services;
		try {
			services = new IppPrintServiceLookup(new URI(prov.getprinter_uri()), prov.getprinter_username(), prov.getprinter_password()).getPrintServices();
		} catch (URISyntaxException e) {
			throw new AdempiereException("Possibly malformed URL - " + prov.getprinter_uri(), e);
		}
		
		LinkedList<MPrinter> rtn = new LinkedList<>();
		for (var s : services) {
			MPrinter printer = new MPrinter(Env.getCtx(), 0, trxName);
			printer.setValue(s.getName());
			printer.setName(s.getName());
			printer.setPrinterNameIpp(s.getName());
			printer.setprinter_uri(prov.getprinter_uri());
			
			rtn.add(printer);
		}
		return rtn;
	}

	@Override
	public void run(PrinterConfig conf) throws Exception {
		
		Doc doc = new SimpleDoc(conf.doc,
				DocFlavor.INPUT_STREAM.PDF,
				new HashDocAttributeSet());
		
		PrintService service = new IppPrintService(new URI(conf.provider.getprinter_uri() + "/printers/" + conf.printer.getPrinterNameIpp()));
		var job = service.createPrintJob();
		
		PrintRequestAttributeSet prats = getAttributes(service, conf.printerconfig_id);	
	    
		prats.add (new Copies(conf.copyparams.copies));
		Locale locale = Language.getLoginLanguage().getLocale();
		prats.add(new JobName(Integer.toString(conf.copyparams.record_id), locale));
		prats.add(PrintUtil.getJobPriority(1, conf.copyparams.copies, false));
		
		job.print(doc, prats);               
	}

	@Override
	public String getProviderValue() {
		return "cups";
	}
	
	@Override
	public void lookupAttributes(MPrinter printer, String trxname) throws Exception {
		// TODO Auto-generated method stub
		
		//Find printer
		PrintService service = findPrinter(printer);
		
		//Find supported attributes
		Class<?>[] classes = service.getSupportedAttributeCategories();
		if (classes == null)
			throw new Exception("Something went wrong; no supported attribute categories");
		
		//Combine Categories and permitted values
		HashMap<Class, Object> attributeValues = findAttributeValues(service, classes);
		if (attributeValues == null || attributeValues.isEmpty())
			throw new Exception("Something went wrong; no supported attribute values");
		
		//Convert to <String, Object>; being <IppName, Attributevalues>
		HashMap<String, Object> ippAttVal = convMap(attributeValues);
		
		//Write entries
		writeNewEntries(printer, service, ippAttVal, trxname); 
	}

	
	@SuppressWarnings("unchecked")
	private static PrintRequestAttributeSet getAttributes(PrintService service, int printerconfig_id) throws SQLException {
		PrintRequestAttributeSet as = null;
		final String sql = 
				"select attrname.printerattributename attributename, " +
				"       coalesce (attrvalue.printerattributevalue, attr.printerattributevalue) attributevalue " + 
				"  from sbsp_printerconfigattr attr " + 
				"  left join sbsp_attributename attrname on  attr.sbsp_attributename_id = attrname.sbsp_attributename_id " + 
				"  left outer join sbsp_attributevalue attrvalue on attr.sbsp_attributevalue_id = attrvalue.sbsp_attributevalue_id " + 
				"  where attr.sbsp_printerconfig_id = ?";
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		pstmt = DB.prepareStatement(sql, null);
		pstmt.setInt(1, printerconfig_id);
		
		rs = pstmt.executeQuery();
		
		as = new HashPrintRequestAttributeSet();
		
		while(rs.next()) { 
			
			
			String attValue = rs.getString("attributevalue"); 
			String attName = rs.getString("attributename");
			
			if  (rs.getString("attributename").equals("media-source")) {
				Attribute[] suppAttr = (Attribute[]) service.getSupportedAttributeValues(IppAttributeName.MEDIA_SOURCE_SUPPORTED.getCategory(), null, null);
				for (Attribute at : suppAttr) {
					if (at.toString().equals(attValue)) {
						as.add(new LdMediaTray(attValue));
						break;
					}
				}						
			} else {

				Object a = null;
				
				IppAttributeName ippattribute = IppAttributeName.get(attName);
				
				Class<?> attrClass = ippattribute.getAttributeClass(); 

				try {
					if (TextSyntax.class.isAssignableFrom(attrClass))
						a = attrClass.getDeclaredConstructor(String.class, Locale.class).newInstance(attValue, new Locale("de_DE"));
					else if (IntegerSyntax.class.isAssignableFrom(attrClass))
						a = attrClass.getDeclaredConstructor(int.class).newInstance(Integer.parseInt(attValue));
					else if (EnumSyntax.class.isAssignableFrom(attrClass))
						a = new EnumSubtitute(rs.getString("attributename"), attValue);
					else
						CLogger.get().warning("Empty attribute");
				} catch (InvocationTargetException | InstantiationException | IllegalAccessException | IllegalArgumentException | NoSuchMethodException | SecurityException e) {
					CLogger.get().log(Level.WARNING, "Couldn't create attribute: " + attrClass + " (" + attValue + ")", e);
				}
				
				as.add((Attribute) a);
			}
		}
		DB.close(rs, pstmt);
        rs = null; pstmt = null;
		return as;
	}

	/**
	 * Finds the desired printer by name
	 * @return The desired printer; NULL if it doesn't exist
	 * @throws Exception 
	 */
	private static PrintService findPrinter (MPrinter printer) throws Exception {
		
		PrintService rtnPS = null; //PrintService to be returned
		
		MPrinterProvider prov = (MPrinterProvider) printer.getsbsp_printerprovider();
		
		//try server connection if a printer name was given
		if (printer.getPrinterNameIpp() != null && !printer.getPrinterNameIpp().equalsIgnoreCase("")) {
			System.getProperties().setProperty(IppPrintServiceLookup.URI_KEY, (prov.getprinter_uri()==null)?"":prov.getprinter_uri());
			System.getProperties().setProperty(IppPrintServiceLookup.USERNAME_KEY, (prov.getprinter_username()==null)?"":prov.getprinter_username()); //Nullpointerexception here
			System.getProperties().setProperty(IppPrintServiceLookup.PASSWORD_KEY, (prov.getprinter_password()==null)?"":prov.getprinter_password());
			
			PrintService[] serv = new IppPrintServiceLookup().getPrintServices();
			if (serv != null && serv.length > 0)
		        for (PrintService s : serv)
		        	if (s.getName().equalsIgnoreCase(printer.getPrinterNameIpp())) {
		        		rtnPS = s; 
		        		break;
		        	}
	        
		}

		if (rtnPS == null)
        	throw new Exception("No such printer in that location");
        
		return rtnPS;
	}
	
	/**
	 * Creates a map of all attributes and their respective values for easier lookup
	 * @param service The target printservice
	 * @param classes A list of allowed attribute categories
	 * @return a hashmap containing categories and supported values
	 */
	@SuppressWarnings("unchecked")
	private static HashMap<Class, Object> findAttributeValues (PrintService service, Class[] classes) {
		
		HashMap<Class, Object> attributeValues = new HashMap<Class, Object>();
		for (Class c : classes) {
			if(c.equals(LdMediaTray.class))
				attributeValues.put(c, service.getSupportedAttributeValues(MediaSourceSupported.class, null, null));
			else
				attributeValues.put(c, service.getSupportedAttributeValues(c, null, null));
		}
		
		return attributeValues;
	}
	
	private static HashMap<String, Object> convMap (HashMap<Class, Object> map) {

		HashMap<String, Object> rtn = new HashMap<String, Object>();
		
		for (Map.Entry<Class, Object> e : map.entrySet()) {
			
			Attribute[] temp;
			
			if (e.getValue().getClass().isArray())
				temp = (Attribute[])(e.getValue());
			else
				temp = new Attribute[] {(Attribute)e.getValue()};
			
			
			if (temp.length == 0)
				CLogger.get().severe("Length 0 on: " + e.getKey() + "");
			else {
				String cat = (temp[0].getName().contains("-supported"))?temp[0].getName().substring(0, temp[0].getName().length() - "-supported".length()):temp[0].getName();
				rtn.put(cat, e.getValue());
			}
			
		}
		
		return rtn;
	}
	
	/**
	 * Makes new entries
	 * @param service
	 * @param attributeValues
	 */
	private static void writeNewEntries (MPrinter printer, PrintService service, HashMap<String, Object> attributeValues, String trxname) throws Exception {
		
		List<String> exCatNames = existingCategories(printer); //Existing categories
		List<String> exAttNames = existingAttributes(); //Existing attributes
		
		//Write new entries
		for (Map.Entry<String, Object> entry : attributeValues.entrySet()) {
			
			if (entry.getValue() == null) //Unnecessary without applicable values
				continue;
			
			MPrinterAttributeName category = new MPrinterAttributeName(Env.getCtx(), 0, trxname);
			
			String c = findCat(entry.getKey(), exCatNames);
			
			if (c == null) {
				
				
				//set all non-null values without defaults
				category.setName(entry.getKey());
				category.setsbsp_printer_ID(printer.get_ID());
				
				category.setPrinterAttributeName(entry.getKey());
				
				//save category after setting
				category.saveEx();
			} else {
				category.setsbsp_attributename_ID(Integer.parseInt(c.split("::")[0]));
			}
			
			
			//do values afterwards
			MPrinterAttributeValue val = null;
			Object[] attr = null;
			
			
			if (entry.getValue().getClass().isArray()) {
				attr = (Object[])entry.getValue();
			} else {
				attr = new Object[] {entry.getValue()};
			}
			
			for (Object o : attr) {
				
				if(exAttNames.contains(o.toString()))
					continue;
				
				val = new MPrinterAttributeValue(Env.getCtx(), 0, trxname);
				val.setName(o.toString());
				val.setPrinterAttributeValue(o.toString());
				val.setsbsp_attributename_ID(category.getsbsp_attributename_ID());
				
				val.saveEx();
				
				val = null;
			}

			category = null;
			
		} 
	}
	
	
	/**
	 * Finds a category by name in a list of categories
	 * @param cat The name of the desired category
	 * @param catList A list of IDs and categories in form 'id:name:
	 * @return ID and Name in form 'id::name'; NULL if not contained
	 */
	private static String findCat (String cat, List<String> catList) {
		
		String rtn = null;
		
		for (String s : catList) {
			if (s.split("::")[1].equalsIgnoreCase(cat) || (cat.equalsIgnoreCase(LdMediaTray.class.getName()) && s.split("::")[1].equalsIgnoreCase("media-source")))
				rtn = s;
		}
		
		return rtn;
	}
	
	
	private static List<String> existingCategories (MPrinter printer) {
		
		List<String> rtn = new ArrayList<String>();
		
		String sql = "SELECT " + I_sbsp_attributename.COLUMNNAME_sbsp_attributename_ID + "," + I_sbsp_attributename.COLUMNNAME_PrinterAttributeName + "," + I_sbsp_attributename.COLUMNNAME_sbsp_printer_ID + " FROM " + I_sbsp_attributename.Table_Name;
		PreparedStatement ps = DB.prepareStatement(sql, null);
		ResultSet rs = null;
		
		try {
			rs = ps.executeQuery();
			while(rs.next()) {
				if (rs.getInt(I_sbsp_attributename.COLUMNNAME_sbsp_printer_ID) == printer.get_ID())
					rtn.add(rs.getInt(I_sbsp_attributename.COLUMNNAME_sbsp_attributename_ID) + "::" + rs.getString(I_sbsp_attributename.COLUMNNAME_PrinterAttributeName));
			}
		} catch (SQLException e) {
			CLogger.get().severe(e.toString());
		} finally {
			DB.close(rs, ps);
			rs = null;
			ps = null;
		} 
		
		return rtn;
	}
	
	
	/**
	 * Lists names of existing attributes and values
	 * @return ArrayList of type string, containing existing attributes
	 */
	private static List<String> existingAttributes () {
		
		//FIXME FIXME FIXME FIXME FIXME FIXME
		List<String> rtn = new ArrayList<String>();
		
		String sql = "SELECT " + I_sbsp_attributevalue.COLUMNNAME_PrinterAttributeValue + " FROM " + I_sbsp_attributevalue.Table_Name;
		PreparedStatement ps = DB.prepareStatement(sql, null);
		ResultSet rs = null;
		
		try {
			rs = ps.executeQuery();
			while (rs.next()) {
				rtn.add(rs.getString(I_sbsp_attributevalue.COLUMNNAME_PrinterAttributeValue));
			}
		} catch (SQLException e) {
			CLogger.get().severe(e.toString());
		} finally {
			DB.close(rs, ps);
			ps = null;
			rs = null;
		}
		
		return rtn;
	}
	
}

package providerImpl;

import javax.print.attribute.Attribute;
import javax.print.attribute.DocAttribute;
import javax.print.attribute.EnumSyntax;
import javax.print.attribute.PrintJobAttribute;
import javax.print.attribute.PrintRequestAttribute;
import javax.print.attribute.PrintServiceAttribute;

import de.lohndirekt.print.attribute.IppAttributeName;

/**
 * A substitution for any EnumSyntax print attribute.
 * To be used for dynamic coding; for any hard code, the existing fields are preferable
 * 
 * @author Lukas Heidbreder, Martin Schoenbeck Beratungen GmbH
 *
 */
public class EnumSubtitute extends EnumSyntax
		implements Attribute, DocAttribute, PrintJobAttribute, PrintRequestAttribute, PrintServiceAttribute {

	private static final long serialVersionUID = -4008035349807374402L;
	
	//Category of attribute; Ipp-Name
	private String cat = "";
	//Value of attribute as String
	private String val = "";
	
	private EnumSubtitute(int value) {
		super(value);
		
	}
	
	public EnumSubtitute (String cat, String val) {
		super(0);
		this.cat = cat;
		this.val = val;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Class<? extends Attribute> getCategory() {
		return IppAttributeName.get(cat).getCategory();
	}

	@Override
	public String getName() {
		return cat;
	}

	
	@Override
	public String toString() {
		return val;
	}
}

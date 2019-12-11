package edu.ucsd.sbrg.parsers;

import static edu.ucsd.sbrg.bigg.ModelPolisher.mpMessageBundle;
import static java.text.MessageFormat.format;
import static org.sbml.jsbml.util.Pair.pairOf;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.sbml.jsbml.Compartment;
import org.sbml.jsbml.Model;
import org.sbml.jsbml.SBMLDocument;
import org.sbml.jsbml.Species;
import org.sbml.jsbml.Unit;
import org.sbml.jsbml.UnitDefinition;
import org.sbml.jsbml.ext.fbc.FBCConstants;
import org.sbml.jsbml.ext.fbc.FBCModelPlugin;
import org.sbml.jsbml.ext.fbc.FBCReactionPlugin;
import org.sbml.jsbml.ext.fbc.FBCSpeciesPlugin;
import org.sbml.jsbml.ext.fbc.FluxObjective;
import org.sbml.jsbml.ext.fbc.GeneProduct;
import org.sbml.jsbml.ext.fbc.Objective;
import org.sbml.jsbml.ext.groups.Group;
import org.sbml.jsbml.ext.groups.GroupsConstants;
import org.sbml.jsbml.ext.groups.GroupsModelPlugin;
import org.sbml.jsbml.util.ModelBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.zbit.util.Utils;
import edu.ucsd.sbrg.bigg.BiGGId;
import edu.ucsd.sbrg.parsers.models.Annotation;
import edu.ucsd.sbrg.parsers.models.Gene;
import edu.ucsd.sbrg.parsers.models.Metabolite;
import edu.ucsd.sbrg.parsers.models.Notes;
import edu.ucsd.sbrg.parsers.models.Reaction;
import edu.ucsd.sbrg.parsers.models.Root;
import edu.ucsd.sbrg.util.SBMLUtils;
import edu.ucsd.sbrg.util.UpdateListener;

/**
 * @author Thomas Jakob Zajac
 */
public class JSONparser {

  private static final String GENE_PRODUCT_PREFIX = "G";
  private static final String REACTION_PREFIX = "R";
  private static final String METABOLITE_PREFIX = "M";
  /**
   * A {@link Logger} for this class.
   */
  private static final transient Logger logger = Logger.getLogger(JSONparser.class.getName());
  /**
   * Regex pattern for biomass prefix exclusion
   */
  private static Pattern PATTERN_BIOMASS_CASE_INSENSITIVE = Pattern.compile("(.*)([Bb][Ii][Oo][Mm][Aa][Ss][Ss])(.*)");

  /**
   * 
   */
  public JSONparser() {
    super();
  }


  /**
   * @param jsonFile,
   *        to be read and parsed
   * @return parsed {@link SBMLDocument}
   * @throws IOException
   */
  public static SBMLDocument read(File jsonFile) throws IOException {
    JSONparser parser = new JSONparser();
    return parser.parse(jsonFile);
  }


  /**
   * Creates the {@link ModelBuilder}, {@link SBMLDocument} and reads the
   * jsonFile as a tree
   * 
   * @param jsonFile
   * @return
   */
  private SBMLDocument parse(File jsonFile) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    Root root = mapper.readValue(jsonFile, Root.class);
    ModelBuilder builder = new ModelBuilder(3, 1);
    SBMLDocument doc = builder.getSBMLDocument();
    doc.addTreeNodeChangeListener(new UpdateListener());
    // Has to be present
    String modelId = correctId(root.getId());
    // Set model name to id, if name is not provided
    String modelName = Optional.ofNullable(root.getName()).orElse(modelId);
    builder.buildModel(modelId, modelName);
    Model model = builder.getModel();
    model.setId(modelId);
    model.setName(modelName);
    parseModel(builder, root);
    return doc;
  }


  /**
   * Sets all informational fields for the model (id, name, annotation, notes,
   * version), generates a basic unit definition (mmol_per_gDW_per_hr) and calls
   * the parse methods for the main fields (compartments, metabolites, genes,
   * reactions)
   * 
   * @param builder
   * @param root
   * @return
   */
  private void parseModel(ModelBuilder builder, Root root) {
    logger.info(mpMessageBundle.getString("JSON_PARSER_STARTED"));
    // get Model and set all informational fields
    Model model = builder.getModel();
    model.setVersion(root.getVersion());
    String annotations = parseAnnotation(root.getAnnotation());
    String notes = parseNotes(root.getNotes());
    // TODO: set annotation and notes
    // Generate basic unit:
    UnitDefinition ud = builder.buildUnitDefinition("mmol_per_gDW_per_hr", null);
    ModelBuilder.buildUnit(ud, 1d, -3, Unit.Kind.MOLE, 1d);
    ModelBuilder.buildUnit(ud, 1d, 0, Unit.Kind.GRAM, -1d);
    ModelBuilder.buildUnit(ud, 3600d, 0, Unit.Kind.SECOND, -1d);
    // parse main fields
    parseCompartments(builder, root.getCompartments().get());
    parseMetabolites(builder, root.getMetabolites());
    parseGenes(builder, root.getGenes());
    parseReactions(builder, root.getReactions());
  }


  private String parseAnnotation(Annotation annotation) {
    // TODO: implement
    return "";
  }


  private String parseNotes(Notes notes) {
    // TODO: implement
    return "";
  }


  /**
   * @param builder
   * @param compartments
   */
  private void parseCompartments(ModelBuilder builder, Map<String, String> compartments) {
    int compSize = compartments.size();
    logger.info(format(mpMessageBundle.getString("NUM_COMPART"), compSize));
    Model model = builder.getModel();
    for (Map.Entry<String, String> compartment : compartments.entrySet()) {
      Compartment comp = model.createCompartment(compartment.getKey());
      comp.setName(compartment.getValue());
    }
  }


  /**
   * @param builder
   * @param metabolites
   */
  private void parseMetabolites(ModelBuilder builder, List<Metabolite> metabolites) {
    int metSize = metabolites.size();
    logger.info(format(mpMessageBundle.getString("NUM_METABOLITES"), metSize));
    Model model = builder.getModel();
    for (Metabolite metabolite : metabolites) {
      String id = metabolite.getId();
      if (!id.isEmpty()) {
        parseMetabolite(model, metabolite);
      }
    }
  }


  /**
   * @param model
   * @param metabolite
   */
  private void parseMetabolite(Model model, Metabolite metabolite) {
    String id = metabolite.getId();
    BiGGId biggId = new BiGGId(correctId(id));
    if (!biggId.isSetPrefix() && !PATTERN_BIOMASS_CASE_INSENSITIVE.matcher(biggId.toBiGGId()).find()) {
      biggId.setPrefix(METABOLITE_PREFIX);
    }
    Species species = model.createSpecies(biggId.toBiGGId());
    String name = metabolite.getName();
    if (name.isEmpty()) {
      name = id;
    }
    species.setName(name);
    String formula = Optional.ofNullable(metabolite.getFormula()).orElse("");
    int charge = metabolite.getCharge();
    FBCSpeciesPlugin specPlug = (FBCSpeciesPlugin) species.getPlugin(FBCConstants.shortLabel);
    if (!formula.isEmpty()) {
      try {
        specPlug.setChemicalFormula(formula);
      } catch (IllegalArgumentException exc){
        logger.severe(String.format("Invalid for formula for metabolite '%s' : %s", id, formula));
      }
    }
    specPlug.setCharge(charge);
    species.setCompartment(metabolite.getCompartment());
    // constraint sense is specified in former parser, not specified in scheme, thus ignored for now
    String annotation = parseAnnotation(metabolite.getAnnotation());
    // TODO: parse annotation
    String notes = parseNotes(metabolite.getNotes());
    // TODO: parse notes
    if (species.isSetAnnotation()) {
      species.setMetaId(species.getId());
    }
  }


  /**
   * @param builder
   * @param genes
   */
  private void parseGenes(ModelBuilder builder, List<Gene> genes) {
    int genSize = genes.size();
    logger.info(format(mpMessageBundle.getString("NUM_GENES"), genSize));
    Model model = builder.getModel();
    for (Gene gene : genes) {
      String id = gene.getId();
      if (!id.isEmpty()) {
        parseGene(model, gene);
      }
    }
  }


  /**
   * @param model
   * @param gene
   */
  private void parseGene(Model model, Gene gene) {
    String id = gene.getId();
    BiGGId biggId = new BiGGId(correctId(id));
    if (!biggId.isSetPrefix()) {
      biggId.setPrefix(GENE_PRODUCT_PREFIX);
    }
    FBCModelPlugin modelPlug = (FBCModelPlugin) model.getPlugin(FBCConstants.shortLabel);
    GeneProduct gp = modelPlug.createGeneProduct(biggId.toBiGGId());
    gp.setLabel(id);
    String name = gene.getName();
    if (name.isEmpty()) {
      name = id;
    }
    gp.setName(name);
    String annotation = parseAnnotation(gene.getAnnotation());
    // TODO: parse annotation
    String notes = parseNotes(gene.getNotes());
    // TODO: parse notes
  }


  /**
   * @param builder
   * @param reactions
   */
  private void parseReactions(ModelBuilder builder, List<Reaction> reactions) {
    int reactSize = reactions.size();
    logger.info(format(mpMessageBundle.getString("NUM_REACTIONS"), reactSize));
    for (Reaction reaction : reactions) {
      String id = reaction.getId();
      if (!id.isEmpty()) {
        parseReaction(builder, reaction);
      }
    }
  }


  /**
   * @param builder
   * @param reaction
   */
  private void parseReaction(ModelBuilder builder, Reaction reaction) {
    Model model = builder.getModel();
    String id = reaction.getId();
    BiGGId biggId = new BiGGId(correctId(id));
    if (!biggId.isSetPrefix()) {
      biggId.setPrefix(REACTION_PREFIX);
    }
    org.sbml.jsbml.Reaction r = model.createReaction(biggId.toBiGGId());
    String name = reaction.getName();
    if (name.isEmpty()) {
      name = id;
    }
    r.setName(name);
    setReactionFluxBounds(builder, reaction, r);
    setReactionStoichiometry(reaction, model, r);
    String geneReactionRule = reaction.getGeneReactionRule();
    if (!geneReactionRule.isEmpty()) {
      SBMLUtils.parseGPR(r, geneReactionRule, false);
    }
    createSubsystem(model, reaction, r);
    setObjectiveCoefficient(reaction, model, r);
    String Annotation = parseAnnotation(reaction.getAnnotation());
    // TODO: parse annotation
    String Notes = parseNotes(reaction.getNotes());
    // TODO: parse notes
  }


  /**
   * @param builder
   * @param reaction
   * @param r
   */
  private void setReactionFluxBounds(ModelBuilder builder, Reaction reaction, org.sbml.jsbml.Reaction r) {
    FBCReactionPlugin rPlug = (FBCReactionPlugin) r.getPlugin(FBCConstants.shortLabel);
    double lowerBound = reaction.getLowerBound();
    // used the definition of reversibility given by the cobrapy sbml module
    if (lowerBound < 0) {
      r.setReversible(true);
    }
    double upperBound = reaction.getUpperBound();
    rPlug.setLowerFluxBound(
      builder.buildParameter(r.getId() + "_lb", r.getId() + "_lb", lowerBound, true, (String) null));
    rPlug.setUpperFluxBound(
      builder.buildParameter(r.getId() + "_ub", r.getId() + "_ub", upperBound, true, (String) null));
  }


  /**
   * @param reaction
   * @param model
   * @param r
   */
  private void setReactionStoichiometry(Reaction reaction, Model model, org.sbml.jsbml.Reaction r) {
    Map<String, Double> metabolites = reaction.getMetabolites().get();
    for (Map.Entry<String, Double> metabolite : metabolites.entrySet()) {
      // removed mu code, as unused not not matching schema
      BiGGId metId = new BiGGId(correctId(metabolite.getKey()));
      if (!PATTERN_BIOMASS_CASE_INSENSITIVE.matcher(metId.toBiGGId()).find()) {
        metId.setPrefix(METABOLITE_PREFIX);
      }
      double value = metabolite.getValue();
      if (value != 0d) {
        Species species = model.getSpecies(metId.toBiGGId());
        if (species == null) {
          species = model.createSpecies(metId.toBiGGId());
          logger.info(format(mpMessageBundle.getString("SPECIES_UNDEFINED"), metId, r.getId()));
        }
        if (value < 0d) {
          ModelBuilder.buildReactants(r, pairOf(-value, species));
        } else {
          ModelBuilder.buildProducts(r, pairOf(value, species));
        }
      }
    }
  }


  /**
   * @param model
   * @param reaction
   * @param r
   */
  private void createSubsystem(Model model, Reaction reaction, org.sbml.jsbml.Reaction r) {
    String name = r.getName();
    String subsystem = Optional.ofNullable(reaction.getSubsystem()).orElse("");
    if (!subsystem.isEmpty()) {
      GroupsModelPlugin groupsModelPlugin = (GroupsModelPlugin) model.getPlugin(GroupsConstants.shortLabel);
      Group group = null;
      for (Group existingGroup : groupsModelPlugin.getListOfGroups()) {
        if (name.equals(existingGroup.getName())) {
          group = existingGroup;
          break;
        }
      }
      if (group == null) {
        group = groupsModelPlugin.createGroup();
        group.setName(name);
        group.setKind(Group.Kind.partonomy);
      }
      SBMLUtils.createSubsystemLink(r, group.createMember());
    }
  }


  /**
   * @param reaction
   * @param model
   * @param r
   */
  private void setObjectiveCoefficient(Reaction reaction, Model model, org.sbml.jsbml.Reaction r) {
    FBCModelPlugin fbc = (FBCModelPlugin) model.getPlugin(FBCConstants.shortLabel);
    Objective obj = fbc.getObjective(0);
    if (obj == null) {
      obj = fbc.createObjective("obj");
      obj.setType(Objective.Type.MAXIMIZE);
      fbc.getListOfObjectives().setActiveObjective(obj.getId());
    }
    double coefficient = reaction.getObjectiveCoefficient();
    if (coefficient != 0d) {
      FluxObjective fo = obj.createFluxObjective("fo_" + r.getId());
      fo.setCoefficient(coefficient);
      fo.setReaction(r);
    }
  }


  /**
   * @param annotation
   * @return
   */
  private String checkAnnotation(String annotation) {
    if (annotation.startsWith("<")) {
      return annotation;
    } else {
      return "<annotation>" + annotation + "</annotation>";
    }
  }


  /**
   * @param notes
   * @return
   */
  private String checkNotes(String notes) {
    if (notes.startsWith("<")) {
      return notes;
    } else {
      return "<notes>" + notes + "</notes>";
    }
  }


  /**
   * Copied from COBRAparser: Checks id strings for BiGGId conformity and
   * modifies them if needed
   * 
   * @param id
   * @return
   */
  private String correctId(String id) {
    StringBuilder newId = new StringBuilder(id.length() + 4);
    char c = id.charAt(0);
    // Must start with letter or '_'.
    if (!(((c >= 97) && (c <= 122)) || ((c >= 65) && (c <= 90)) || c == '_')) {
      newId.append("_");
    }
    // May contain letters, digits or '_'
    for (int i = 0; i < id.length(); i++) {
      c = id.charAt(i);
      if (((c == ' ') || (c < 48) || ((57 < c) && (c < 65)) || ((90 < c) && (c < 97)))) {
        if (i < id.length() - 1) {
          newId.append('_'); // Replace spaces and special characters
          // with "_"
        }
      } else {
        newId.append(c);
      }
    }
    if (!newId.toString().equals(id)) {
      logger.fine(format(mpMessageBundle.getString("CHANGED_METABOLITE_ID"), id, newId));
    }
    return newId.toString();
  }


  /**
   * @param exc
   */
  private void logException(Exception exc) {
    logger.warning(format("{0}: {1}", exc.getClass().getSimpleName(), Utils.getMessage(exc)));
  }
}

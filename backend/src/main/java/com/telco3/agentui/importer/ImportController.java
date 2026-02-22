package com.telco3.agentui.importer;

import com.telco3.agentui.domain.Entities.*;
import com.telco3.agentui.domain.*;
import com.telco3.agentui.vicidial.VicidialClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController @RequestMapping("/api/vicidial/leads")
public class ImportController {
  private final CustomerRepository customers; private final CustomerPhoneRepository phones; private final VicidialClient vicidial;
  public ImportController(CustomerRepository customers, CustomerPhoneRepository phones, VicidialClient vicidial){this.customers=customers;this.phones=phones;this.vicidial=vicidial;}

  @PostMapping("/import")
  Map<String,Object> importCsv(@RequestParam("file") MultipartFile file) throws Exception {
    int ok=0, fail=0; List<String> errors=new ArrayList<>();
    var lines=new String(file.getBytes(), StandardCharsets.UTF_8).split("\\R");
    for(int idx=1; idx<lines.length; idx++){
      if(lines[idx].isBlank()) continue;
      try {
        var c=lines[idx].split(",");
        String dni=c[0].trim(), first=c[1].trim(), last=c[2].trim(), phone=c[3].trim(), listId=c[4].trim();
        var cust=customers.findByDni(dni).orElseGet(()->{var nc=new CustomerEntity(); nc.dni=dni; return nc;});
        cust.firstName=first; cust.lastName=last; customers.save(cust);
        if(phones.findByPhoneNumber(phone).isEmpty()){ var p=new CustomerPhoneEntity(); p.customerId=cust.id; p.phoneNumber=phone; p.isPrimary=true; phones.save(p); }
        vicidial.addLead(phone,first,last,dni,listId); ok++;
      } catch(Exception e){ fail++; errors.add("line "+(idx+1)+": "+e.getMessage()); }
    }
    return Map.of("imported",ok,"failed",fail,"errors",errors);
  }
}
